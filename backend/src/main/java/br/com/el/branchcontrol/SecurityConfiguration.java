package br.com.el.branchcontrol;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

import javax.sql.DataSource;
import java.util.List;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UsersStore usersStore(DataSource source, PasswordEncoder encoder) {
        return new UsersStore(source, encoder);
    }
    @Bean UserDetailsService userDetailsService(UsersStore users) {
        return username -> new LocalPrincipal(users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas.")));
    }
    @Bean AuthenticationManager authenticationManager(UserDetailsService details, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }
    @Bean SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, UsersStore users,
            SecurityContextRepository contexts, CsrfTokenRepository csrf, DatabaseInstanceLease lease) throws Exception {
        http.securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
            .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .requestCache(config -> config.disable())
            .formLogin(config -> config.disable()).httpBasic(config -> config.disable())
            .authorizeHttpRequests(config -> config
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/error").permitAll()
                .requestMatchers("/api/auth/session", "/api/auth/login", "/api/auth/setup").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority(Groups.ADM)
                .requestMatchers("/api/config").hasAuthority(Groups.ADM)
                .requestMatchers("/api/config", "/api/repositories", "/api/repositories/**", "/api/plans", "/api/plans/**")
                    .authenticated()
                .requestMatchers("/api/environments", "/api/environments/**").authenticated()
                .requestMatchers("/api/runs", "/api/runs/**", "/api/reports", "/api/reports/**", "/api/reports.csv", "/api/auth/**")
                    .authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(config -> config
                .authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Entre para acessar a aplicação.\"}");
                })
                .accessDeniedHandler((request, response, ex) -> {
                    response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
                    String code = ex instanceof CsrfException ? "CSRF" : "FORBIDDEN";
                    String message = ex instanceof CsrfException ? "Formulário expirado. Atualize a página e tente novamente." : "Seu grupo não tem permissão para esta operação.";
                    response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
                }))
            .logout(config -> config.logoutUrl("/api/auth/logout").invalidateHttpSession(true)
                .deleteCookies("BRANCHCONTROL_SESSION")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"authenticated\":false}");
                }))
            .addFilterAfter(new AccountStateFilter(users), SecurityContextHolderFilter.class)
            .addFilterBefore(new LeaseGuardFilter(lease), CsrfFilter.class);
        return http.build();
    }
}
