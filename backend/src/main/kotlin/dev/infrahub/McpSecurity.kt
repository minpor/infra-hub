package dev.infrahub

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.web.filter.OncePerRequestFilter

@Configuration
@Profile("!worker & !deployer")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class McpSecurity(private val access: McpAccess, @param:Value("\${HUB_PUBLIC_URL:https://infra-hub.minpor.ru}") private val publicUrl: String) {
    @Bean
    @Order(1)
    fun mcpChain(http: HttpSecurity): SecurityFilterChain = http
        .securityMatcher("/mcp", "/mcp/**")
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .csrf { it.disable() }
        .requestCache { it.disable() }
        .authorizeHttpRequests { it.anyRequest().authenticated() }
        .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
        .addFilterBefore(object : OncePerRequestFilter() {
            override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
                response.setHeader("Cache-Control", "no-store")
                val origin = request.getHeader("Origin")
                if (origin != null && origin != publicUrl.trimEnd('/')) { response.sendError(403); return }
                val header = request.getHeader("Authorization") ?: ""
                val identity = if (header.startsWith("Bearer ")) access.authenticate(header.removePrefix("Bearer ")) else null
                if (identity == null) {
                    response.setHeader("WWW-Authenticate", "Bearer realm=\"infra-hub-mcp\"")
                    response.sendError(401)
                    return
                }
                SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(identity, null, emptyList())
                chain.doFilter(request, response)
            }
        }, UsernamePasswordAuthenticationFilter::class.java)
        .build()
}
