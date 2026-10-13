package com.shen.hospital.consultation.dto;

import jakarta.validation.constraints.Size;

public record CreateSessionRequest(@Size(max = 100) String title) {
}
