package com.shen.hospital.knowledge.domain;

import lombok.Data;

@Data
public class KnowledgeHitRow {
    private Long id;
    private String title;
    private String sourceDescription;
    private String documentVersion;
    private String content;
    private Integer pageStart;
    private Integer pageEnd;
    private Integer paragraphStart;
    private Integer paragraphEnd;
}
