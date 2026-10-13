package com.shen.hospital.knowledge.dto;
public record KnowledgeHit(String chunkId, String title, String sourceDescription, String documentVersion,
        String excerpt, Integer pageStart, Integer pageEnd, Integer paragraphStart, Integer paragraphEnd,
        double score, String spaceKey) {}
