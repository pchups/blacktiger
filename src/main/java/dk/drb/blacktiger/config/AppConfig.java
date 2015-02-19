package dk.drb.blacktiger.config;

import com.thetransactioncompany.cors.CORSFilter;
import dk.drb.blacktiger.security.RestAuthenticationEntryPoint;
import dk.drb.blacktiger.security.StoredProcedureAuthenticationProvider;
import dk.drb.blacktiger.util.SimpleFilterConfig;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.web.access.channel.ChannelProcessingFilter;

/**
 * Configuration Entry Point for the application.
 */
@Configuration
@PropertySource({"classpath:blacktiger.properties","file:${user.home}/blacktiger.properties"})
@Import({ServiceConfig.class, WebsocketConfig.class, ControllerConfig.class})
//@ImportResource("classpath:springsecurity.xml")
@EnableWebSecurity
public class AppConfig extends WebSecurityConfigurerAdapter { 

    private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);
    private static final String PROPERTY_ENCRYPTION_KEY = "encryptionKey";
    
    @Autowired
    private DataSource asteriskDataSource;
    
    @Resource
    private Environment env;
    
    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        LOG.info("Retrieving authentication manager bean.");
        return super.authenticationManagerBean();
    }
    
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        LOG.info("Configuring HTTPSecurity");
        http.addFilterBefore(getCorsFilter(), ChannelProcessingFilter.class)
                .authorizeRequests().antMatchers("/system/passwordRequests").permitAll()
                .and().authorizeRequests().antMatchers("/rooms/**").authenticated()
                .and().authorizeRequests().antMatchers("/system/**").authenticated()
                .and().authorizeRequests().antMatchers("/phonebook/**").authenticated()
                .and().authorizeRequests().antMatchers("/reports/**").authenticated()
                .and().httpBasic().authenticationEntryPoint(new RestAuthenticationEntryPoint())
                //.and().requiresChannel().anyRequest().requiresSecure()
                .and().csrf().disable();
    }
    
    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        LOG.info("Configuring authentication manager.");
        StoredProcedureAuthenticationProvider spap = new StoredProcedureAuthenticationProvider();
        spap.setDataSource(asteriskDataSource);
        spap.setEncryptionKey(env.getProperty(PROPERTY_ENCRYPTION_KEY));
        
        String adminUser = "admin"; //env.getProperty(PROPERTY_NAME_ADMIN_USER);
        String adminPass = "123"; //env.getProperty(PROPERTY_NAME_ADMIN_PASSWORD);

        auth.inMemoryAuthentication()
                .withUser(adminUser).password(adminPass).roles("ADMIN").and().
                and().authenticationProvider(spap);
    }
    
    private CORSFilter getCorsFilter() throws ServletException {
        LOG.info("Building CORS filter.");
        CORSFilter cors = new CORSFilter();
        Map<String, String> config = new HashMap<>();

        config.put("cors.allowOrigin", "*");
        config.put("cors.supportedMethods", "GET, POST, HEAD, PUT, DELETE, OPTIONS, HEAD");
        config.put("cors.supportedHeaders", "Content-Type, X-Requested-With, Authorization, Accept, Origin,Access-Control-Request-Method,Access-Control-Request-Headers");
        config.put("cors.supportsCredentials", "true");
        config.put("cors.exposedHeaders", "Access-Control-Allow-Origin,Access-Control-Allow-Credentials");
        config.put("cors.maxAge", "10");
        cors.init(new SimpleFilterConfig("cors", null, config));
        return cors;
    }
    
}
