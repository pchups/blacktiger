package dk.drb.blacktiger.repository.asterisk;

import dk.drb.blacktiger.model.Participant;
import dk.drb.blacktiger.model.ParticipantJoinEvent;
import dk.drb.blacktiger.model.ParticipantLeaveEvent;
import dk.drb.blacktiger.model.CallType;
import dk.drb.blacktiger.model.ConferenceEndEvent;
import dk.drb.blacktiger.model.ConferenceEvent;
import dk.drb.blacktiger.model.ConferenceStartEvent;
import dk.drb.blacktiger.model.ParticipantCommentRequestCancelEvent;
import dk.drb.blacktiger.model.ParticipantCommentRequestEvent;
import dk.drb.blacktiger.model.ParticipantMuteEvent;
import dk.drb.blacktiger.model.ParticipantUnmuteEvent;
import dk.drb.blacktiger.model.Room;
import dk.drb.blacktiger.repository.ConferenceRoomRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.asteriskjava.live.AsteriskServer;
import org.asteriskjava.manager.EventTimeoutException;
import org.asteriskjava.manager.ResponseEvents;
import org.asteriskjava.manager.TimeoutException;
import org.asteriskjava.manager.action.AbstractManagerAction;
import org.asteriskjava.manager.action.ConfbridgeKickAction;
import org.asteriskjava.manager.action.ConfbridgeListAction;
import org.asteriskjava.manager.action.ConfbridgeListRoomsAction;
import org.asteriskjava.manager.action.ConfbridgeMuteAction;
import org.asteriskjava.manager.action.ConfbridgeUnmuteAction;
import org.asteriskjava.manager.action.EventGeneratingAction;
import org.asteriskjava.manager.action.ManagerAction;
import org.asteriskjava.manager.event.ConfbridgeEndEvent;
import org.asteriskjava.manager.event.ConfbridgeJoinEvent;
import org.asteriskjava.manager.event.ConfbridgeLeaveEvent;
import org.asteriskjava.manager.event.ConfbridgeListEvent;
import org.asteriskjava.manager.event.ConfbridgeListRoomsEvent;
import org.asteriskjava.manager.event.ConfbridgeStartEvent;
import org.asteriskjava.manager.event.ConnectEvent;
import org.asteriskjava.manager.event.DisconnectEvent;
import org.asteriskjava.manager.event.DtmfEvent;
import org.asteriskjava.manager.event.ManagerEvent;
import org.asteriskjava.manager.event.ResponseEvent;
import org.asteriskjava.manager.response.ManagerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * This implementation of ConferenceRepository uses the Confbridge conference in Asterisk.
 */
public class Asterisk11ConfbridgeRepository extends AbstractAsteriskConferenceRepository implements ConferenceRoomRepository {

    private class ChannelRoomEntry {
        private final boolean host;
        private final String roomId;

        public ChannelRoomEntry(boolean host, String roomId) {
            this.host = host;
            this.roomId = roomId;
        }

        public boolean isHost() {
            return host;
        }

        public String getRoomId() {
            return roomId;
        }
        
    }
    private static final Logger LOG = LoggerFactory.getLogger(Asterisk11ConfbridgeRepository.class);
    private static final String DIGIT_COMMENT_REQUEST = "1";
    private static final String DIGIT_COMMENT_REQUEST_CANCEL = "0";

    private final Queue<ManagerEvent> managerEvents = new LinkedList<>();
    
    /**
     * A map between channels and rooms. 
     * This is used for DTMF events which never carries the conference room which the user sending the DTMF event resides in.
     */
    private final Map<String, ChannelRoomEntry> channelRoomMap = new HashMap<>();
    
    public Asterisk11ConfbridgeRepository() {
        LOG.debug("Instantiating AsteriskConfbridgeRepository");
        setManagerEventListener(this);
    }

    @Override
    public void onManagerEvent(final ManagerEvent event) {
        LOG.debug("Manager event recieved and being added to queue. [event={}]", event);
        try {
            managerEvents.add(event);
        } catch (IllegalStateException ex) {
            LOG.error("Unable to add event to queue.", ex);
        }

    }

    @Scheduled(fixedDelay = 50)
    protected void handleEventQueue() {
        ManagerEvent event = null;
        while ((event = managerEvents.poll()) != null) {
            LOG.debug("Handling event from queue. [event={}]", event);
            if (event instanceof ConfbridgeJoinEvent) {
                onConfbridgeJoinEvent((ConfbridgeJoinEvent) event);
            }
            if (event instanceof ConfbridgeLeaveEvent) {
                onConfbridgeLeaveEvent((ConfbridgeLeaveEvent) event);
            }
            if (event instanceof ConfbridgeStartEvent) {
                onConfbridgeStart((ConfbridgeStartEvent) event);
            }

            if (event instanceof ConfbridgeEndEvent) {
                onConfbridgeEnd((ConfbridgeEndEvent) event);
            }

            if (event instanceof DtmfEvent) {
                onDtmfEvent((DtmfEvent) event);
            }
            
            if(event instanceof DisconnectEvent) {
                onDisconnectEvent((DisconnectEvent) event);
            }
            
            if(event instanceof ConnectEvent) {
                reload();
            }
        }
    }

    private void onDisconnectEvent(DisconnectEvent event) {
        LOG.info("Disconnect event recieved. Informing all partakers thats participants have left and Conference rooms have ended.");
        for(Room room : findAll()) {
            for(Participant participant : findByRoomNo(room.getId())) {
                Asterisk11ConfbridgeRepository.this.fireEvent(new ParticipantLeaveEvent(room.getId(), participant));
            }
            Asterisk11ConfbridgeRepository.this.fireEvent(new ConferenceEndEvent(room.getId()));
        }
        reset();
    }
    
    private void onDtmfEvent(DtmfEvent event) {
        if (event.isEnd() && (event.getDigit().equals(DIGIT_COMMENT_REQUEST) || event.getDigit().equals(DIGIT_COMMENT_REQUEST_CANCEL))) {
            // A DTMF event has been received. We need to retrieve roomId and callerId. 
            ChannelRoomEntry roomEntry = channelRoomMap.get(event.getChannel());
            if(roomEntry.isHost()) {
                LOG.debug("DTMF Event from host in {} received. Ignoring it.", roomEntry.getRoomId());
                return;
            }
            
            ConferenceEvent ce = null;
            switch (event.getDigit()) {
                case DIGIT_COMMENT_REQUEST:
                    LOG.debug("Dtmf Event is a Comment Request.");
                    ce = new ParticipantCommentRequestEvent(roomEntry.getRoomId(), normalizeChannelName(event.getChannel()));
                    break;
                case DIGIT_COMMENT_REQUEST_CANCEL:
                    LOG.debug("Dtmf Event is a Comment Cancel Request.");
                    ce = new ParticipantCommentRequestCancelEvent(roomEntry.getRoomId(), normalizeChannelName(event.getChannel()));
                    break;
            }

            Asterisk11ConfbridgeRepository.this.fireEvent(ce);
        }
    }

    private void onConfbridgeJoinEvent(ConfbridgeJoinEvent event) {
        String roomNo = event.getConference();
        Participant p = participantFromEvent(event);
        channelRoomMap.put(event.getChannel(), new ChannelRoomEntry(p.isHost(), roomNo));
        Asterisk11ConfbridgeRepository.this.fireEvent(new ParticipantJoinEvent(roomNo, p));
    }

    private void onConfbridgeLeaveEvent(ConfbridgeLeaveEvent event) {
        String roomNo = event.getConference();
        
        Participant p = participantFromEvent(event);
        channelRoomMap.remove(event.getChannel());

        Asterisk11ConfbridgeRepository.this.fireEvent(new ParticipantLeaveEvent(roomNo, p));
        
    }

    private void onConfbridgeStart(ConfbridgeStartEvent e) {
        LOG.debug("Handling ConfbridgeStartEvent [event={}]", e);
        Room room = new Room(e.getConference());
        Asterisk11ConfbridgeRepository.this.fireEvent(new ConferenceStartEvent(room));
    }

    private void onConfbridgeEnd(ConfbridgeEndEvent e) {
        LOG.debug("Handling ConfbridgeEndEvent [event={}]", e);
        Asterisk11ConfbridgeRepository.this.fireEvent(new ConferenceEndEvent(e.getConference()));
    }
    
    private void onMuted(String conference, String channel) {
        LOG.debug("Handling onMuted. [room={};channel={}]", conference, channel);
        Asterisk11ConfbridgeRepository.this.fireEvent(new ParticipantMuteEvent(conference, channel));
    }

    private void onUnmuted(String conference, String channel) {
        LOG.debug("Handling onUnmuted. [room={};channel={}]", conference, channel);
        Asterisk11ConfbridgeRepository.this.fireEvent(new ParticipantUnmuteEvent(conference, channel));
    }
    
    @Override
    public void setAsteriskServer(AsteriskServer asteriskServer) {
        super.setAsteriskServer(asteriskServer);

        LOG.info("Asterisk Server specified. Reading rooms from server.");

        reload();
    }

    
    private void reset() {
        this.channelRoomMap.clear();
    }
    
    private void reload() {
        reset();
        
        for(Room room : readRoomsFromServer()) {
            Asterisk11ConfbridgeRepository.this.fireEvent(new ConferenceStartEvent(room));
            for(Participant participant : findByRoomNo(room.getId())) {
                Asterisk11ConfbridgeRepository.this.fireEvent(new ParticipantJoinEvent(room.getId(), participant));
            }
        }
    }
    
    /**
     * Reads rooms directly from the asterisk server.
     */
    private List<Room> readRoomsFromServer() {
        LOG.debug("Reading rooms from server.");
        ResponseEvents events = sendAction(new ConfbridgeListRoomsAction());

        List<Room> result = new ArrayList();

        for (ResponseEvent event : events.getEvents()) {
            if (event instanceof ConfbridgeListRoomsEvent) {
                ConfbridgeListRoomsEvent roomsEvent = (ConfbridgeListRoomsEvent) event;
                result.add(new Room(roomsEvent.getConference()));
            }
        }
        LOG.debug("Rooms returned from server: {}", result.size());
        return result;
    }

    private List<Participant> readParticipantsFromServer(String roomId) {
        LOG.debug("Reading participants from server. [room={}]", roomId);
        ResponseEvents events = sendAction(new ConfbridgeListAction(roomId));
        
        if(events.getResponse() != null && events.getResponse().getResponse().equals("Error")) {
            LOG.error("Error occured when sending ConfBridgeListAction to Asterisk. [{}]", events.getResponse());
            return null;
        }
        
        List<Participant> result = new ArrayList<>();

        for (ResponseEvent event : events.getEvents()) {
            if (event instanceof ConfbridgeListEvent) {
                LOG.debug("ConfbridgeListEvent received [{}]", event);
                ConfbridgeListEvent confbridgeListEvent = (ConfbridgeListEvent) event;
                Participant p = participantFromEvent(confbridgeListEvent);
                result.add(p);
                channelRoomMap.put(confbridgeListEvent.getChannel(), new ChannelRoomEntry(p.isHost(), roomId));
            }
        }
        LOG.debug("Participants returned from server: {}", result.size());
        return result;
    }

    @Override
    public Room findOne(String id) {
        LOG.debug("Retrieving room [id={}]", id);
        List<Participant> participants = readParticipantsFromServer(id);
        if(participants == null) {
            return null;
        } else {
            return new Room(id);
        }
    }

    @Override
    public List<Room> findAll() {
        LOG.debug("Retreiving all rooms.");
        return readRoomsFromServer();
    }

    @Override
    public List<Room> findAllByIds(List<String> ids) {
        List<Room> subSelection = new ArrayList<>();
        List<Room> rooms = findAll();
        for(Room room : rooms) {
            if(ids.contains(room.getId())) {
                subSelection.add(room);
            }
        }
        return subSelection;
    }
    
    

    @Override
    public List<Participant> findByRoomNo(String roomNo) {
        LOG.debug("Listing participants. [room={}]", roomNo);
        return readParticipantsFromServer(roomNo);
    }

    @Override
    public Participant findByRoomNoAndChannel(String roomNo, String channel) {
        LOG.debug("Retrieving participant. [room={};channel={}]", roomNo, channel);
        List<Participant> participants = findByRoomNo(roomNo);
        for (Participant p : participants) {
            if (channel.equals(p.getChannel())) {
                return p;
            }
        }
        return null;
    }

    @Override
    public void kickParticipant(String roomNo, String channel) {
        LOG.debug("Kicking participant [room={};channel={}]", roomNo, channel);
        ManagerResponse response = sendAction(new ConfbridgeKickAction(roomNo, denormalizeChannelName(channel)));
    }

    @Override
    public void muteParticipant(String roomNo, String channel) {
        LOG.debug("Muting participant [room={};channel={}]", roomNo, channel);
        setMutenessOfParticipant(roomNo, channel, true);
        onMuted(roomNo, channel);
    }

    @Override
    public void unmuteParticipant(String roomNo, String channel) {
        LOG.debug("Unmuting participant [room={};channel={}]", roomNo, channel);
        setMutenessOfParticipant(roomNo, channel, false);
        onUnmuted(roomNo, channel);
    }


    private void setMutenessOfParticipant(String roomId, String channel, boolean value) {
        String denormChannel = denormalizeChannelName(channel);
        
        LOG.debug("Setting muteness for channel. [room={};channel={},value={}]", new Object[]{roomId, channel, value});
        AbstractManagerAction a = value == true ? new ConfbridgeMuteAction(roomId, denormChannel) : new ConfbridgeUnmuteAction(roomId, denormChannel);
        ManagerResponse response = sendAction(a);
        
        if("Error".equals(response.getResponse())) {
            LOG.error("Unable to mute participant. Asterisk responded: " + response.getMessage());
            throw new InvalidDataAccessResourceUsageException(response.getMessage());
        }
        
    }

    private Participant participantFromEvent(ConfbridgeListEvent event) {
        return participantFromEventData(event.getConference(), event.getChannel(), event.getCallerIDnum(), event.getCallerIdName(), event.getDateReceived());
    }

    private Participant participantFromEvent(ConfbridgeJoinEvent event) {
        return participantFromEventData(event.getConference(), event.getChannel(), event.getCallerIdNum(), event.getCallerIdName(), event.getDateReceived());
    }

    private Participant participantFromEvent(ConfbridgeLeaveEvent event) {
        return participantFromEventData(event.getConference(), event.getChannel(), event.getCallerIdNum(), event.getCallerIdName(), event.getDateReceived());
    }

    private Participant participantFromEventData(String conference, String channel, String callerIdNum, String callerIdName, Date dateReceived) {
        LOG.debug("Resolving participant object from event data.");
        boolean host = false;
        CallType callType = CallType.Sip;

        if (callerIdNum != null && callerIdNum.equals(conference)) {
            host = true;
        }

        boolean muted = !host;
        String phoneNumber = callerIdNum;
        String name = null;

        return new Participant(normalizeChannelName(channel), callerIdNum, name, phoneNumber, muted, host, callType, dateReceived);
    }
    
    private ManagerResponse sendAction(ManagerAction action) {
        LOG.debug("Sending ManagerAction to server [action={}]", action);
        try {
            return asteriskServer.getManagerConnection().sendAction(action);
        } catch (IOException | IllegalArgumentException | IllegalStateException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }

    private ResponseEvents sendAction(EventGeneratingAction action) {
        LOG.debug("Sending EventGeneratingAction to server [action={}]", action);
        try {
            return asteriskServer.getManagerConnection().sendEventGeneratingAction(action, 1000);
        } catch (IOException | EventTimeoutException | IllegalArgumentException | IllegalStateException ex) {
            throw new RuntimeException(ex);
        }
    }
    
    private String normalizeChannelName(String channel) {
        String normalized = channel.replace("/", "___");
        normalized = normalized.replace(".", "---");
        LOG.debug("Normalizing channel name [from={};to={}]", channel, normalized);
        return normalized;
    }
    
    private String denormalizeChannelName(String channel) {
        String denormalized = channel.replace("___", "/");
        denormalized = denormalized.replace("---", ".");
        LOG.debug("Denormalizing channel name [from={};to={}]", channel, denormalized);
        return denormalized;
    }
}
