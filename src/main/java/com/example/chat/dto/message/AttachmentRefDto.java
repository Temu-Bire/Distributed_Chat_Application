package com.example.chat.dto.message;

/**
 * Reference to a file already minted by the upload service. The server
 * re-reads name, type and size from its own sidecar metadata, so a client
 * can lie only about which of its own files it points at - never about what
 * that file is.
 */
public record AttachmentRefDto(String url) {
}
