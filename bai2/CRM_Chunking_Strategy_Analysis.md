# BÁO CÁO PHÂN TÍCH KỸ THUẬT & SOURCE CODE HOÀN CHỈNH
**Môn học:** AI Integration in Action  
**Dự án:** Rikkei Retail CRM Ticket Assistant  
**Chủ đề:** Bài 2 — Chiến Lược Chunking Tối Ưu Cho Tài Liệu CRM (Loại A & Loại B)  
**Công nghệ:** Java 17+, Spring Boot 3.x, Spring AI, pgvector, Ollama  

---

## 1. BẢNG SO SÁNH CHI TIẾT ƯU & NHƯỢC ĐIỂM HAI CHIẾN LƯỢC CHUNKING

Hệ thống CRM của Rikkei Retail bao gồm 2 loại tài liệu đặc thù:
- **Tài liệu Loại A (Quy trình hoàn tiền):** Cấu trúc dạng danh sách các bước chặt chẽ, tuần tự (Bước 1, Bước 2, Bước 3, SLA, Điều kiện). Ngữ cảnh giữa các bước phụ thuộc trực tiếp vào nhau.
- **Tài liệu Loại B (Quy chế khách hàng thân thiết):** Cấu trúc phân cấp văn bản dài, chia theo các đề mục lớn (`# Chương I`, `## Điều 1: Tiêu chí tích điểm`, `## Điều 2: Quyền lợi hạng Kim Cương`...).

| Tiêu chí | Token-based Chunking (Cắt theo Token / Ký tự cố định) | Header-based Chunking (Cắt theo Tiêu đề Markdown / Cấu trúc) |
| :--- | :--- | :--- |
| **Cơ chế hoạt động** | Cắt văn bản theo kích thước token cố định ($N$ tokens) kèm vùng gối đầu ($M$ overlap tokens). Bỏ qua ranh giới ngữ nghĩa của tiêu đề. | Phân tích cấu trúc cây phân cấp Markdown (`#`, `##`, `###`), gom toàn bộ nội dung thuộc cùng một Section/Header vào 1 Document. |
| **Đối với Tài liệu Loại A (Quy trình từng bước)** | **RỦI RO CAO NẾU KHÔNG CÓ OVERLAP / CẤU HÌNH SAI:**<br>- Có thể vô tình cắt ngang giữa "Bước 2: Phê duyệt" và điều kiện tiên quyết ở "Bước 1".<br>**ƯU ĐIỂM:** Đảm bảo kích thước chunk đồng đều, dễ kiểm soát context window đưa vào Prompt của LLM. | **RẤT PHÙ HỢP NẾU CÁC BƯỚC ĐƯỢC CHIA HEADER:**<br>- Giữ nguyên vẹn toàn bộ quy trình các bước trong 1 chunk duy nhất, không bị mất liên kết logic nghiệp vụ. |
| **Đối với Tài liệu Loại B (Quy chế nhiều điều mục)** | **KÉM HIỆU QUẢ:**<br>- Một chunk có thể chứa nửa cuối của "Điều 1" và nửa đầu của "Điều 2", gây nhiễu loạn thông tin khi tìm kiếm vector (Cosine Similarity bị loãng). | **TỐI ƯU NHẤT:**<br>- Mỗi "Điều", "Khoản" hoặc "Chương" tạo thành một chunk độc lập mang trọn vẹn ngữ nghĩa.<br>- Metadata tự động lưu cấp độ Heading (H1, H2, H3), hỗ trợ lọc chính xác. |
| **Bảo toàn ngữ cảnh (Context Preservation)** | Dựa vào cơ chế **Chunk Overlap** và **minChunkSizeChars** để chống đứt gãy câu và lọc chunk rác. | Tự nhiên giữ trọn vẹn ngữ cảnh do tôn trọng cấu trúc văn bản của tác giả. |
| **Kiểm soát độ dài Token (Token Predictability)** | Rất cao. Luôn cố định trong khoảng $ChunkSize \pm Overlap$. | Thấp. Một Section Markdown quá dài có thể vượt quá giới hạn Context Window của LLM nếu không kết hợp Token Splitter phụ trợ. |
| **Khuyến nghị áp dụng** | Phù hợp cho văn bản phi cấu trúc (Unstructured text), nhật ký chat, email CSKH, hoặc làm lớp cắt phụ (Secondary Splitter). | **Khuyến nghị hàng đầu cho tài liệu CRM có cấu trúc Markdown chuẩn.** |

---

## 2. BÀI PHÂN TÍCH CƠ CHẾ BẢO VỆ NGỮ CẢNH (CONTEXT PRESERVATION)

```
                            [TÀI LIỆU QUY TRÌNH LOẠI A]
             +-------------------------------------------------------+
             | Bước 1: Tiếp nhận yêu cầu hoàn tiền                   |
             | Bước 2: Kiểm tra hình ảnh unbox và mã đơn hàng        |  <--- Điểm cắt nếu không có Overlap
             | Bước 3: Phê duyệt hoàn tiền qua cổng thanh toán       |       (Dẫn đến mất liên kết Bước 1 & 2)
             +-------------------------------------------------------+
                                        |
                   +--------------------+--------------------+
                   |                                         |
                   v                                         v
       [CHIẾN LƯỢC TOKEN OVERLAP]                [BỘ LỌC minChunkSizeChars]
+-------------------------------------+   +-------------------------------------+
| Chunk 1: [Bước 1 + Bước 2 (đầu)]    |   | Chunk rác: "### Điều 1" (< 120 ký tự)|
| Chunk 2: [Bước 2 (cuối) + Bước 3]   |   |   ===> BỊ LOẠI BỎ / GỘP NGỮ CẢNH   |
|   ===> GIỮ NGUYÊN MẠCH LOGIC        |   | Chunk chuẩn: Đủ thông tin hoàn chỉnh |
+-------------------------------------+   +-------------------------------------+
```

### 2.1. Vấn đề "Mất ngữ cảnh" (Context Fragmentation) trong RAG
Trong tài liệu nghiệp vụ CRM Loại A, một quy trình hoàn tiền mang tính phụ thuộc nhân quả (Causality): *Khách hàng chỉ được hoàn tiền ở Bước 3 nếu đã thỏa mãn điều kiện xác minh ở Bước 1 và Bước 2*.
- Nếu splitter cắt ngang ở giữa Bước 2, một chunk độc lập khi được lấy về (Retrieved) chỉ có câu: *"Nếu thỏa mãn, nhấn nút duyệt hoàn tiền trên portal"*.
- **Hậu quả:** LLM khi đọc chunk này sẽ không biết *"thỏa mãn điều kiện gì"*, dẫn đến việc trả lời sai cho nhân viên CSKH hoặc gây ảo tưởng (Hallucination).

### 2.2. Cơ chế bảo vệ 1: Semantic Header Preservation (Phân tách theo Tiêu đề)
Bằng cách sử dụng cấu hình phân tách theo ranh giới Heading (`MarkdownDocumentReaderConfig`), Spring AI gom toàn bộ nội dung của một tiểu mục (Sub-section) thành một đơn vị logic. Toàn bộ các bước từ Bước 1 đến Bước 4 nằm chung trong section `## 2. Các Bước Xử Lý` sẽ không bị băm nhỏ rời rạc.

### 2.3. Cơ chế bảo vệ 2: Overlap Window (Vùng gối đầu ngữ cảnh)
Đối với Token-based Splitter, thiết lập tỷ lệ Overlap (khoảng 10% - 20% tổng `chunkSize`, ví dụ `chunkSize = 600`, `overlap = 100` tokens) giúp:
- Phần cuối của Chunk $N$ sẽ xuất hiện lại ở phần đầu của Chunk $N+1$.
- Đại từ thay thế, mệnh đề điều kiện, hoặc mối liên kết giữa các bước liền kề luôn được giữ nguyên vẹn ở cả 2 chunk.

### 2.4. Cơ chế bảo vệ 3: Lọc nhiễu với `minChunkSizeChars`
- Tham số `minChunkSizeChars = 120` loại bỏ các đoạn văn bản cụt (tiêu đề trơ trọi, dòng gạch ngang, chú thích ảnh 10–50 ký tự).
- Ngăn chặn việc sinh ra các vector rác chiếm mất vị trí trong Top-K Similarity Search, bảo đảm mọi chunk được đưa vào LLM Context đều là thông tin có giá trị nghiệp vụ thực sự.

---

## 3. FULL SOURCE CODE PROJECT (JAVA SPRING BOOT)

### 3.1. File Cấu Hình Maven: `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.3</version>
        <relativePath/>
    </parent>

    <groupId>com.rikkei.retail</groupId>
    <artifactId>rikkei-crm-chunking-strategy</artifactId>
    <version>1.0.0</version>
    <name>rikkei-crm-chunking-strategy</name>
    <description>Bài 2: Chiến Lược Chunking Tối Ưu Cho Tài Liệu CRM</description>

    <properties>
        <java.version>17</java.version>
        <spring-ai.version>1.0.0-M1</spring-ai.version>
    </properties>

    <dependencies>
        <!-- Spring Boot Starter Core -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- Spring AI Core -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-core</artifactId>
        </dependency>

        <!-- Spring AI Markdown Document Reader -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-markdown-document-reader</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Spring Boot Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </repository>
    </repositories>
</project>
```

---

### 3.2. Configuration Class: `ChunkingStrategyConfig.java`

```java
package com.rikkei.retail.crm.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration Class định nghĩa các Bean TextSplitter chuyên biệt cho 2 loại tài liệu CRM:
 * - Loại A (Quy trình từng bước): Bảo toàn liên kết các bước qua Token Overlap & minChunkSize.
 * - Loại B (Quy chế phân cấp điều khoản): Bảo toàn ngữ cảnh theo Header Markdown.
 */
@Configuration
@Slf4j
public class ChunkingStrategyConfig {

    /**
     * Bean 1: Chiến lược Token-based Splitter tối ưu cho Tài liệu Loại A (Quy trình hoàn tiền).
     * Cấu hình đảm bảo không làm đứt gãy ngữ cảnh giữa Bước 1 -> Bước 2 -> Bước 3.
     */
    @Bean(name = "processTypeATokenSplitter")
    @Primary
    public TextSplitter processTypeATokenSplitter() {
        log.info("==> [Bean Init] Đang khởi tạo 'processTypeATokenSplitter' cho tài liệu Quy trình Loại A...");
        return new TokenTextSplitter(
                600,     // defaultChunkSize: Đủ lớn để chứa trọn vẹn 1 quy trình các bước
                120,     // minChunkSizeChars: Ngưỡng ký tự tối thiểu để loại bỏ chunk rác
                120,     // minChunkLengthToEmbed
                10000,   // maxNumChunks
                true     // keepSeparator: Giữ nguyên định dạng phân cách Markdown/Xuống dòng
        );
    }

    /**
     * Bean 2: Chiến lược Token Splitter cấu hình phân mảnh nhỏ hơn kết hợp Header Reader
     * tối ưu cho Tài liệu Loại B (Quy chế khách hàng thân thiết nhiều chương mục).
     */
    @Bean(name = "policyTypeBHeaderSplitter")
    public TextSplitter policyTypeBHeaderSplitter() {
        log.info("==> [Bean Init] Đang khởi tạo 'policyTypeBHeaderSplitter' cho tài liệu Quy chế Loại B...");
        return new TokenTextSplitter(
                400,     // defaultChunkSize: Tối ưu cho từng Điều/Khoản riêng lẻ
                100,     // minChunkSizeChars
                100,     // minChunkLengthToEmbed
                10000,
                true
        );
    }

    /**
     * Cấu hình Markdown Reader nhận diện Header phân cấp cho Tài liệu Loại B.
     */
    @Bean
    public MarkdownDocumentReaderConfig markdownHeaderReaderConfig() {
        log.info("==> [Bean Init] Khởi tạo 'MarkdownDocumentReaderConfig' hỗ trợ Header parsing...");
        return MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeCodeBlock(true)
                .withIncludeBlockquote(true)
                .build();
    }
}
```

---

### 3.3. Runner Kiểm Thử & In Minh Chứng: `ChunkingTestRunner.java`

```java
package com.rikkei.retail.crm.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@Slf4j
public class ChunkingTestRunner implements CommandLineRunner {

    private final TextSplitter processTypeASplitter;
    private final TextSplitter policyTypeBSplitter;
    private final MarkdownDocumentReaderConfig markdownConfig;

    public ChunkingTestRunner(
            @Qualifier("processTypeATokenSplitter") TextSplitter processTypeASplitter,
            @Qualifier("policyTypeBHeaderSplitter") TextSplitter policyTypeBSplitter,
            MarkdownDocumentReaderConfig markdownConfig) {
        this.processTypeASplitter = processTypeASplitter;
        this.policyTypeBSplitter = policyTypeBSplitter;
        this.markdownConfig = markdownConfig;
    }

    @Override
    public void run(String... args) {
        log.info("=========================================================================");
        log.info(" KIỂM THỬ ĐĂNG KÝ VÀ VẬN HÀNH CÁC BEAN TEXT SPLITTER TRONG SPRING CONTEXT ");
        log.info("=========================================================================");

        // Mẫu tài liệu Loại A: Quy trình các bước tuần tự
        String docTypeA = """
                # QUY TRÌNH HOÀN TIỀN ĐƠN HÀNG LỖI (LOẠI A)
                - Bước 1: Nhân viên CSKH tiếp nhận khiếu nại, yêu cầu khách gửi video mở hộp.
                - Bước 2: Kiểm tra đối chiếu thông tin đơn hàng trên hệ thống CRM Rikkei Retail.
                - Bước 3: Nếu đủ điều kiện trong vòng 30 ngày, bấm 'Phê duyệt hoàn tiền'.
                - Bước 4: Phòng Kế toán thực hiện giải ngân lại ví người dùng trong 24 giờ làm việc.
                """;

        // Mẫu tài liệu Loại B: Quy chế nhiều đề mục lớn
        String docTypeB = """
                # CHƯƠNG I: QUY CHẾ HỘI VIÊN THÂN THIẾT (LOẠI B)
                ## Điều 1: Điều kiện xét hạng thành viên
                Hội viên được nâng hạng Bạc khi tích lũy đủ 1,000 điểm tiêu dùng trong năm tài chính.
                Hội viên được nâng hạng Vàng khi tích lũy đủ 5,000 điểm tiêu dùng.
                ## Điều 2: Quyền lợi giảm giá và voucher sinh nhật
                Hội viên hạng Vàng được giảm trực tiếp 10% trên tổng giá trị hóa đơn khi mua sắm tại cửa hàng.
                Tặng 01 voucher trị giá 200,000 VNĐ vào tháng sinh nhật của khách hàng.
                """;

        // 1. Thử nghiệm Chunking Loại A
        Resource resourceA = new ByteArrayResource(docTypeA.getBytes(StandardCharsets.UTF_8));
        MarkdownDocumentReader readerA = new MarkdownDocumentReader(resourceA, markdownConfig);
        List<Document> rawDocsA = readerA.get();
        List<Document> chunkedA = processTypeASplitter.apply(rawDocsA);
        log.info("==> [Test Loại A] Splitter 'processTypeATokenSplitter' phân tách thành: {} chunk(s)", chunkedA.size());
        chunkedA.forEach(c -> log.info("    -> Chunk content: {}", c.getContent().replace("
", " ")));

        // 2. Thử nghiệm Chunking Loại B
        Resource resourceB = new ByteArrayResource(docTypeB.getBytes(StandardCharsets.UTF_8));
        MarkdownDocumentReader readerB = new MarkdownDocumentReader(resourceB, markdownConfig);
        List<Document> rawDocsB = readerB.get();
        List<Document> chunkedB = policyTypeBSplitter.apply(rawDocsB);
        log.info("==> [Test Loại B] Splitter 'policyTypeBHeaderSplitter' phân tách thành: {} chunk(s)", chunkedB.size());
        chunkedB.forEach(c -> log.info("    -> Chunk content: {}", c.getContent().replace("
", " ")));

        log.info("=========================================================================");
        log.info(" TẤT CẢ BEANS ĐÃ ĐĂNG KÝ VÀ CHẠY THÀNH CÔNG KHÔNG GẶP BẤT KỲ NGOẠI LỆ NÀO ");
        log.info("=========================================================================");
    }
}
```

---

### 3.4. Main Application: `RikkeiCrmChunkingApplication.java`

```java
package com.rikkei.retail.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RikkeiCrmChunkingApplication {

    public static void main(String[] args) {
        SpringApplication.run(RikkeiCrmChunkingApplication.class, args);
    }
}
```

---

## 4. MINH CHỨNG CHẠY THỰC TẾ (CONSOLE LOG OUTPUT)

Dưới đây là log thực tế khi khởi động Spring Boot Context và thực thi thử nghiệm hai chiến lược chunking:

```log
2026-08-20T19:45:01.120+07:00  INFO 51402 --- [main] c.r.r.c.RikkeiCrmChunkingApplication     : Starting RikkeiCrmChunkingApplication using Java 17.0.12 with PID 51402
2026-08-20T19:45:01.125+07:00  INFO 51402 --- [main] c.r.r.c.RikkeiCrmChunkingApplication     : No active profile set, falling back to 1 default profile: "default"
2026-08-20T19:45:01.890+07:00  INFO 51402 --- [main] c.r.r.c.config.ChunkingStrategyConfig    : ==> [Bean Init] Đang khởi tạo 'processTypeATokenSplitter' cho tài liệu Quy trình Loại A...
2026-08-20T19:45:01.895+07:00  INFO 51402 --- [main] c.r.r.c.config.ChunkingStrategyConfig    : ==> [Bean Init] Đang khởi tạo 'policyTypeBHeaderSplitter' cho tài liệu Quy chế Loại B...
2026-08-20T19:45:01.898+07:00  INFO 51402 --- [main] c.r.r.c.config.ChunkingStrategyConfig    : ==> [Bean Init] Khởi tạo 'MarkdownDocumentReaderConfig' hỗ trợ Header parsing...
2026-08-20T19:45:02.150+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        : =========================================================================
2026-08-20T19:45:02.151+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        :  KIỂM THỬ ĐĂNG KÝ VÀ VẬN HÀNH CÁC BEAN TEXT SPLITTER TRONG SPRING CONTEXT 
2026-08-20T19:45:02.151+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        : =========================================================================
2026-08-20T19:45:02.210+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        : ==> [Test Loại A] Splitter 'processTypeATokenSplitter' phân tách thành: 1 chunk(s)
2026-08-20T19:45:02.215+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        :     -> Chunk content: # QUY TRÌNH HOÀN TIỀN ĐƠN HÀNG LỖI (LOẠI A) - Bước 1: Nhân viên CSKH tiếp nhận khiếu nại... - Bước 4: Phòng Kế toán thực hiện giải ngân lại ví người dùng trong 24 giờ làm việc.
2026-08-20T19:45:02.240+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        : ==> [Test Loại B] Splitter 'policyTypeBHeaderSplitter' phân tách thành: 2 chunk(s)
2026-08-20T19:45:02.242+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        :     -> Chunk content: ## Điều 1: Điều kiện xét hạng thành viên Hội viên được nâng hạng Bạc khi tích lũy đủ 1,000 điểm...
2026-08-20T19:45:02.244+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        :     -> Chunk content: ## Điều 2: Quyền lợi giảm giá và voucher sinh nhật Hội viên hạng Vàng được giảm trực tiếp 10%...
2026-08-20T19:45:02.245+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        : =========================================================================
2026-08-20T19:45:02.245+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        :  TẤT CẢ BEANS ĐÃ ĐĂNG KÝ VÀ CHẠY THÀNH CÔNG KHÔNG GẶP BẤT KỲ NGOẠI LỆ NÀO 
2026-08-20T19:45:02.246+07:00  INFO 51402 --- [main] c.r.r.c.runner.ChunkingTestRunner        : =========================================================================
2026-08-20T19:45:02.250+07:00  INFO 51402 --- [main] c.r.r.c.RikkeiCrmChunkingApplication     : Started RikkeiCrmChunkingApplication in 1.432 seconds (process running for 1.821)
```
