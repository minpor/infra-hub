package dev.infrahub

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@Configuration
@Profile("!worker & !production")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class LocalSecurity {
    @Bean fun localChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .authorizeHttpRequests { it.anyRequest().permitAll() }
        .build()
}

@Configuration
@Profile("production")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ProductionSecurity(@param:Value("\${hub.owner-github-id}") private val ownerId: Long) {
    @Bean fun ownerUserService(): OAuth2UserService<OAuth2UserRequest, OAuth2User> {
        val delegate = DefaultOAuth2UserService()
        return OAuth2UserService { request ->
            val user = delegate.loadUser(request)
            val id = (user.attributes["id"] as? Number)?.toLong()
            if (id != ownerId) throw OAuth2AuthenticationException(OAuth2Error("owner_only"), "GitHub account is not the configured owner")
            user
        }
    }

    @Bean fun productionChain(http: HttpSecurity): SecurityFilterChain = http
        .authorizeHttpRequests {
            it.requestMatchers("/api/health").permitAll()
                .requestMatchers("/internal/builds/*/source").permitAll() // Separate worker credential and lease checks in WorkerSources.
                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                .anyRequest().authenticated()
        }
        .oauth2Login { login ->
            login.userInfoEndpoint { it.userService(ownerUserService()) }
                .defaultSuccessUrl("/", true)
        }
        .csrf { csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()) }
        .exceptionHandling { it.defaultAuthenticationEntryPointFor(
            HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), AntPathRequestMatcher("/api/**")
        ) }
        .logout { it.logoutUrl("/logout").logoutSuccessUrl("/").invalidateHttpSession(true).deleteCookies("JSESSIONID", "XSRF-TOKEN") }
        .build()
}

@RestController
@Profile("production")
class ProductionSession {
    @GetMapping("/api/session") fun session(principal: Principal): Map<String, String> = mapOf("login" to principal.name)
    @GetMapping("/api/csrf") fun csrf(token: CsrfToken): Map<String, String> = mapOf("token" to token.token)
}
