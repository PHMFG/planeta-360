package br.com.rbs.portal.comum;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Configura a segurança da API: sessão por cookie, CSRF para SPA, rotas protegidas e respostas JSON de erro.
 * Atende o item 3 da Entrega 2. [PERMISSAO] Só login, csrf e ativação são públicos; o resto de /api exige sessão.
 */
@Configuration
public class SegurancaConfig {

    /**
     * BCrypt "puro", sem o prefixo {bcrypt} do DelegatingPasswordEncoder: os hashes da amostra são $2a$10$ e não têm prefixo.
     * [PENDENTE] Se a RBS exigir outro algoritmo (Argon2id), troca-se aqui e rehash-se no próximo login.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Confere e-mail e senha contra o banco (UserDetailsService do pacote acesso). Usado só pelo login manual. */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService usuarios, PasswordEncoder codificador) {
        DaoAuthenticationProvider provedor = new DaoAuthenticationProvider(usuarios);
        provedor.setPasswordEncoder(codificador);
        return new ProviderManager(provedor);
    }

    /**
     * Onde o contexto de segurança fica guardado: na sessão HTTP (cookie JSESSIONID).
     * O login manual (AuthService) grava nele de forma explícita, porque o Spring Security 6 em diante não salva sozinho.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain cadeiaDeSeguranca(HttpSecurity http, SecurityContextRepository repositorio,
                                          RespostasDeSeguranca respostas, RegistraLogout registraLogout)
            throws Exception {
        http
                // Sem tela de login do Spring e sem senha no cabeçalho: o login é a rota JSON POST /api/auth/login.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // CSRF para SPA: cookie XSRF-TOKEN legível pelo JavaScript e cabeçalho X-XSRF-TOKEN em todo POST/PUT/PATCH/DELETE.
                .csrf(csrf -> csrf.spa())

                .securityContext(contexto -> contexto.securityContextRepository(repositorio))
                // Não guarda "a página que o usuário queria" na sessão: numa API isso só criaria sessões à toa.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))

                .authorizeHttpRequests(rotas -> rotas
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // Primeiro acesso com o código de ativação (etapa 4): a pessoa ainda não tem senha, então não tem sessão.
                        .requestMatchers(HttpMethod.POST, "/api/auth/ativar").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // [PENDENTE] Se a API passar a servir o front compilado, liberar aqui os arquivos estáticos.
                        .anyRequest().denyAll())

                .exceptionHandling(erros -> erros
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas))

                // Logout encerra a sessão, apaga o cookie e responde 204 (sem redirecionar).
                .logout(saida -> saida
                        .logoutUrl("/api/auth/logout")
                        .addLogoutHandler(registraLogout)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));

        return http.build();
    }
}
