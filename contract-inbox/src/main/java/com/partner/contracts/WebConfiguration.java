package com.partner.contracts;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.method.HandlerMethod;
import java.util.Map;

@Configuration
class WebConfiguration implements WebMvcConfigurer {
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Content-Type-Options", "nosniff");
                if (handler instanceof HandlerMethod method && method.getBeanType() == ApiController.class && !Directory.exists(request.getHeader("X-User")))
                    throw new ApiException(401, "X-User 헤더에 유효한 가상 사용자 ID가 필요합니다.");
                return true;
            }
        });
    }
    @Bean OpenAPI api() {
        return new OpenAPI().info(new Info().title("파트너 계약 문서함").version("1.0.0"))
            .components(new Components().addSecuritySchemes("identity", new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-User")));
    }
}
@RestController
class OperationsController {
    @GetMapping("/health") Map<String, String> health() { return Map.of("status", "ok"); }
}
@RestControllerAdvice
class ErrorResponses {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("detail", e.getMessage())); }
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.web.method.annotation.HandlerMethodValidationException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.unprocessableEntity().body(Map.of("detail", "입력 내용을 확인해 주세요.")); }
}
