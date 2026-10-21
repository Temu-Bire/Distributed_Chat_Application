package com.example.chat.dto.group;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Optional constraints on a new invite link; both are nullable, meaning the
 * link never expires and can be used without a limit.
 *
 * @param expiresInSeconds lifetime from now, capped at 30 days so a leaked
 *                         link cannot outlive the group it points at
 * @param maxUses          joins this link may cost, capped to keep the
 *                         counter meaningful rather than decorative
 */
public record CreateInviteLinkRequest(
    @Min(value = 60, message = "The link must last at least a minute")
    @Max(value = 2_592_000, message = "The link can last at most 30 days")
    Long expiresInSeconds,

    @Min(value = 1, message = "A link must allow at least one use")
    @Max(value = 10_000, message = "maxUses must be 10000 or less")
    Integer maxUses
) {}
