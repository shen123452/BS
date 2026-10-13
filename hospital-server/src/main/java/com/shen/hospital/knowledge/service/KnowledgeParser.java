package com.shen.hospital.knowledge.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.shen.hospital.common.BusinessException;

@Component
public class KnowledgeParser {
    public static final String SPLIT_VERSION = "text-v1-600-100";
    private static final int CHUNK_CHARS = 600;
    private static final int OVERLAP_CHARS = 100;
    private static final int MAX_PDF_PAGES = 100;

    public record Draft(String content, String titlePath, Integer pageStart, Integer pageEnd,
                        Integer paragraphStart, Integer paragraphEnd) {}
    private record PageText(String text, Integer page) {}

    public List<Draft> parse(String fileName, byte[] bytes) {
        if (fileName == null || bytes == null || bytes.length == 0) throw invalid("请选择非空的TXT或PDF文件");
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) return parsePdf(bytes);
        if (lower.endsWith(".txt")) return parseText(bytes);
        throw invalid("仅支持TXT或文本型PDF文件");
    }

    private List<Draft> parseText(byte[] bytes) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
                throw invalid("TXT包含非文本内容，请上传UTF-8文本资料");
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return chunk(List.of(new PageText(text, null)));
        } catch (CharacterCodingException ex) {
            throw invalid("TXT须使用UTF-8编码");
        }
    }

    private List<Draft> parsePdf(byte[] bytes) {
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-')
            throw invalid("文件内容与PDF格式不匹配");
        try (var document = Loader.loadPDF(bytes)) {
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > MAX_PDF_PAGES) throw invalid("PDF页数须为1到100页");
            var stripper = new PDFTextStripper();
            var sources = new ArrayList<PageText>();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                sources.add(new PageText(stripper.getText(document), page));
            }
            return chunk(sources);
        } catch (IOException ex) {
            throw invalid("PDF无法解析，请上传可复制文字的PDF文件");
        }
    }

    private List<Draft> chunk(List<PageText> pages) {
        var result = new ArrayList<Draft>();
        String currentTitle = null;
        int paragraphNo = 0;
        for (var page : pages) {
            String normalized = page.text().replace("\r\n", "\n").replace('\r', '\n');
            String[] paragraphs = normalized.split("\\n\\s*\\n+");
            for (String raw : paragraphs) {
                String paragraph = raw.replaceAll("[\\t\\u000B\\f]+", " ").strip();
                if (paragraph.isEmpty()) continue;
                String[] lines = paragraph.split("\\n");
                var content = new StringBuilder();
                for (String line : lines) {
                    String value = line.strip();
                    if (value.isEmpty()) continue;
                    if (value.matches("^#{1,6}\\s+.+$")) {
                        currentTitle = value.replaceFirst("^#{1,6}\\s+", "").strip();
                    } else {
                        if (!content.isEmpty()) content.append('\n');
                        content.append(value);
                    }
                }
                if (content.isEmpty()) continue;
                paragraphNo++;
                String value = content.toString();
                for (String part : slice(value)) {
                    result.add(new Draft(part, currentTitle, page.page(), page.page(), paragraphNo, paragraphNo));
                    if (result.size() > 5000) throw invalid("单份资料切分后超过5000个片段，建议拆分文件");
                }
            }
        }
        if (result.isEmpty()) throw invalid("没有提取到可用文本，请上传文本版资料");
        return List.copyOf(result);
    }

    private static List<String> slice(String paragraph) {
        var result = new ArrayList<String>();
        int length = paragraph.codePointCount(0, paragraph.length());
        if (length <= CHUNK_CHARS) return List.of(paragraph);
        int start = 0;
        while (start < length) {
            int end = Math.min(length, start + CHUNK_CHARS);
            int from = paragraph.offsetByCodePoints(0, start);
            int to = paragraph.offsetByCodePoints(0, end);
            String part = paragraph.substring(from, to).strip();
            if (!part.isEmpty()) result.add(part);
            if (end == length) break;
            start = end - OVERLAP_CHARS;
        }
        return result;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT", message);
    }
}
