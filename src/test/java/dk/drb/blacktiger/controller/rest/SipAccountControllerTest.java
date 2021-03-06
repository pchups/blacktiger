package dk.drb.blacktiger.controller.rest;

import static dk.drb.blacktiger.fixture.rest.SipAccountRestDataFixture.*;
import dk.drb.blacktiger.model.SipAccount;
import dk.drb.blacktiger.service.SipAccountService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.any;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 *
 * @author michael
 */
public class SipAccountControllerTest {
    
    MockMvc mockMvc;

    @InjectMocks
    SipAccountController controller;

    @Mock
    SipAccountService service;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        this.mockMvc = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
    }

    @Test
    public void thatEntryCanBeCreated() throws Exception {
        when(service.create(any(SipAccount.class), any(String.class))).thenReturn(Boolean.TRUE);
        this.mockMvc.perform(post("/sipaccounts").content(standardCreateAccountRequestAsJson())
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk());
    }
    
        @Test
    public void thatEntryCanBeRetreived() throws Exception {
        when(service.findOneByKeyAndPhonenumber(eq("123"), eq("+4512345678"))).thenReturn(standard());
        
        this.mockMvc.perform(get("/sipaccounts/{phoneNumber}?key={key}", "+4512345678", "123")
                .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string("{\"name\":\"John Doe\",\"email\":\"john@doe.dk\",\"phoneNumber\":\"+4512345678\",\"sipId\":\"#00000000\",\"sipPass\":\"12345\"}"));
    }
}