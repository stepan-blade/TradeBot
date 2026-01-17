package com.example.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. Настройки для корректной работы H2 Console в браузере
                .headers().frameOptions().sameOrigin()
                .and()

                // 2. Отключаем CSRF (обязательно для H2 и упрощения работы API)
                .csrf().disable()

                .authorizeRequests()
                // Разрешаем доступ к консоли БД и системным путям
                .antMatchers("/h2-console/**").permitAll()
                .antMatchers("/css/**", "/js/**", "/api/**", "/login").permitAll()

                // Защищаем главную и настройки
                .antMatchers("/", "/settings").authenticated()
                .anyRequest().authenticated()
                .and()

                // 3. Форма авторизации
                .formLogin()
                .loginPage("/login")
                .defaultSuccessUrl("/", true) // Перенаправление на главную после входа
                .permitAll()
                .and()

                // 4. Логика выхода из системы
                .logout()
                .logoutSuccessUrl("/login")
                .permitAll();

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails user = User.withDefaultPasswordEncoder()
                .username("admin")
                .password("1")
                .roles("USER")
                .build();

        return new InMemoryUserDetailsManager(user);
    }
}