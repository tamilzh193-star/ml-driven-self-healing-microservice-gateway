package com.selfhealing.inventory.chaos;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
public class ChaosFilter implements Filter {

    private final ChaosState chaosState;

    public ChaosFilter(ChaosState chaosState) {
        this.chaosState = chaosState;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();

        if (path.startsWith("/chaos") || path.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        // 1. Inject latency delay
        int delay = chaosState.getDelayMs();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 2. Inject 500 error
        if (chaosState.isErrorActive()) {
            res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Simulated Chaos Failure: Internal Server Error");
            return;
        }

        chain.doFilter(request, response);
    }
}
