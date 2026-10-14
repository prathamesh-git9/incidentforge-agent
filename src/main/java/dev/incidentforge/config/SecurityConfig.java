package dev.incidentforge.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Least privilege by role: REPORTER files incidents; REVIEWER reads runs and is the only role
 * that can approve, reject, correct or retry publishing; TOOLS (the agent's MCP client) can only
 * reach the read-only MCP endpoint. Stateless HTTP Basic, so CSRF does not apply.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, IncidentForgeProperties properties) throws Exception {
        String mcp = properties.mcp().endpoint();
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/prometheus", "/", "/review.html").permitAll()
                        .requestMatchers(mcp, mcp + "/**").hasRole("TOOLS")
                        .requestMatchers(HttpMethod.POST, "/api/incidents").hasRole("REPORTER")
                        .requestMatchers(HttpMethod.GET, "/api/runs/**").hasAnyRole("REVIEWER", "REPORTER")
                        .requestMatchers(HttpMethod.POST, "/api/runs/**").hasRole("REVIEWER")
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(IncidentForgeProperties properties, PasswordEncoder encoder) {
        List<UserDetails> users = new ArrayList<>();
        for (Map.Entry<String, IncidentForgeProperties.Security.User> entry : properties.security().users().entrySet()) {
            String password = entry.getValue().password();
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("No password configured for user " + entry.getKey());
            }
            users.add(User.withUsername(entry.getKey()).password(encoder.encode(password))
                    .roles(entry.getValue().roles().toArray(String[]::new)).build());
        }
        return new InMemoryUserDetailsManager(users);
    }
}
