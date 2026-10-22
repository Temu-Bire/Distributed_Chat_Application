package com.example.chat.api;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

/**
 * Routes for the HTML pages, which are served from the same origin as the API.
 *
 * <p>A visitor with a live session has already seen the marketing page and is
 * heading for their conversations, so "/" sends them straight to the app. The
 * session is the only signal here: the SPA reads it over /api/auth/status, and
 * a redirect decided on the server removes the flash of a landing page nobody
 * asked for.</p>
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String root(Principal principal) {
        boolean signedIn = principal != null && !(principal instanceof AnonymousAuthenticationToken);
        return signedIn ? "redirect:/app" : "forward:/landing.html";
    }
}
