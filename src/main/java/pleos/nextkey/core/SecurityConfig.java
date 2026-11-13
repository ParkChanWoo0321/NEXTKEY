package pleos.nextkey.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import pleos.nextkey.common.Actor;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwords(){ return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService accounts(){ return ignored->{throw new UsernameNotFoundException("Bearer authentication required");}; }

    @Bean SecurityFilterChain apiSecurity(HttpSecurity http, Credentials credentials) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(r->r.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/auth/token").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->send(res,401,"AUTH_REQUIRED"))
                .accessDeniedHandler((req,res,ex)->send(res,403,"FORBIDDEN")))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
                    throws ServletException,IOException {
                    String header=req.getHeader("Authorization");
                    if (header!=null && header.startsWith("Bearer ")) {
                        String path=req.getRequestURI().substring(req.getContextPath().length());
                        Actor actor=credentials.identify(header.substring(7),path,req.getMethod());
                        if (actor!=null) {
                            var auth=UsernamePasswordAuthenticationToken.authenticated(actor,null,
                                List.of(new SimpleGrantedAuthority("ROLE_"+actor.role())));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }
                    }
                    chain.doFilter(req,res);
                }
            },UsernamePasswordAuthenticationFilter.class).build();
    }
    private static void send(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\""+code+"\"}");
    }
}
