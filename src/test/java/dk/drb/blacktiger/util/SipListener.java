package dk.drb.blacktiger.util;

import net.sourceforge.peers.sip.core.useragent.BaseSipListener;
import net.sourceforge.peers.sip.transport.SipResponse;


public class SipListener extends BaseSipListener {

        Boolean registered = null;
        Boolean pickedUp = null;
                
        @Override
        public void registerFailed(SipResponse sipResponse) {
            registered = false;
        }

        @Override
        public void registerSuccessful(SipResponse sipResponse) {
            registered = true;
        }

        @Override
        public void calleePickup(SipResponse sipResponse) {
            pickedUp = true;
        }

        @Override
        public void error(SipResponse sipResponse) {
            registered = false;
            pickedUp = false;
        }

}
