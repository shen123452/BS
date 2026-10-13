package com.shen.hospital.knowledge;

import com.shen.hospital.common.BusinessException;
import com.shen.hospital.knowledge.service.KnowledgeParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeParserTest {
    private final KnowledgeParser parser = new KnowledgeParser();

    @Test
    void parsesUtf8TextAndKeepsHeadingAndParagraphMetadata() {
        var drafts = parser.parse("guide.txt", "# 呼吸科\n\n咳嗽伴发热时先记录持续时间。".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, drafts.size());
        assertEquals("呼吸科", drafts.getFirst().titlePath());
        assertEquals("咳嗽伴发热时先记录持续时间。", drafts.getFirst().content());
        assertEquals(1, drafts.getFirst().paragraphStart());
    }

    @Test
    void rejectsInvalidEncodingEmptyTextAndFakePdf() {
        assertInvalid(() -> parser.parse("guide.txt", new byte[]{(byte) 0xC3, 0x28}));
        assertInvalid(() -> parser.parse("guide.txt", "\n\t".getBytes(StandardCharsets.UTF_8)));
        assertInvalid(() -> parser.parse("guide.pdf", "not-a-pdf".getBytes(StandardCharsets.UTF_8)));
        assertInvalid(() -> parser.parse("guide.docx", "text".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void splitsLongParagraphWithExactlyOneHundredCodePointOverlap() {
        String value = IntStream.range(0, 900).mapToObj(i -> "字").collect(Collectors.joining());
        var drafts = parser.parse("guide.txt", value.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, drafts.size());
        assertEquals(600, drafts.getFirst().content().codePointCount(0, drafts.getFirst().content().length()));
        assertEquals(400, drafts.get(1).content().codePointCount(0, drafts.get(1).content().length()));
        assertEquals(drafts.getFirst().content().substring(500), drafts.get(1).content().substring(0, 100));
    }

    @Test
    void rejectsMoreThanFiveThousandChunks() {
        String value = IntStream.range(0, 5001).mapToObj(i -> "段落" + i).collect(Collectors.joining("\n\n"));
        var error = assertThrows(BusinessException.class, () -> parser.parse("guide.txt", value.getBytes(StandardCharsets.UTF_8)));
        assertEquals("INVALID_DOCUMENT", error.code());
        assertTrue(error.getMessage().contains("5000"));
    }

    private static void assertInvalid(Runnable action) {
        var error = assertThrows(BusinessException.class, action::run);
        assertEquals("INVALID_DOCUMENT", error.code());
    }
}
