package pleos.nextkey.lifecycle;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ReportPdfTests {
    @Test void embedsKoreanAndPaginatesLongPersonalRecords() throws Exception {
        String configured=System.getenv("NEXTKEY_PDF_FONT");
        Path font=Path.of(configured==null || configured.isBlank() ? "C:/Windows/Fonts/malgun.ttf" : configured);
        Assumptions.assumeTrue(Files.isRegularFile(font),"NEXTKEY_PDF_FONT is required for the PDF rendering check");
        String time="2026-11-12T06:00:00Z";
        Map<String,Object> readings=Map.of("odometer",Map.of("value",32230,"unit","km","source","DEV_SIMULATOR","collectedAt",time,"status","VALID"));
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("sessionId","demo-session");
        report.put("reservationId","demo-booking");
        report.put("vehicle",Map.of("id","dev-car","model","가상 시연 차량"));
        report.put("startedAt",time);
        report.put("endedAt",time);
        report.put("endReceivedAt",time);
        report.put("confirmedEndAt",time);
        report.put("generatedAt",time);
        report.put("startSnapshot",Snapshots.normalize(readings,"FUEL",Instant.parse(time)));
        report.put("endSnapshot",Snapshots.normalize(null,"FUEL",Instant.parse(time)));
        report.put("distanceKm",Map.of("status","UNCONFIRMED"));
        report.put("energyChange",Map.of("status","UNCONFIRMED"));
        report.put("warningChange",Map.of("status","VALID","startCodes",List.of(),"endCodes",List.of("BRAKE_LOW"),
                "newCodes",List.of("BRAKE_LOW")));
        report.put("dataGaps",List.of(Map.of("from",time,"to",time,"reason","POWER_OFF")));
        report.put("syncDelays",List.of(Map.of("from",time,"to",time,"reason","OFFLINE")));
        report.put("records",Map.of("damage",IntStream.rangeClosed(1,25).mapToObj(i->Map.of("id",i,"note","이용자 제보 "+i)).toList()));
        report.put("finalSafety",Map.of("parked",true,"source","DEV_SIMULATOR"));
        report.put("finalSettings",Map.of("state","NO_READINGS"));
        report.put("extensions",List.of(Map.of("status","REJECTED","requestedAt",time,"requestedEndAt",time,
                "previousEndAt",time,"feeConsent","AGREED","agreedAt",time,"operatorDecision","REJECTED",
                "decidedAt",time,"payment","NOT_INTEGRATED")));
        byte[] bytes=new ReportPdf(font.toString()).render(report);
        try(var doc=Loader.loadPDF(bytes)) {
            assertTrue(doc.getNumberOfPages()>1);
            String text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("개인 이용 보고서"));
            assertTrue(text.contains("가상 시연 데이터 포함"));
            assertTrue(text.contains("이용자 제보 25"));
            assertTrue(text.contains("희망 종료"));
            assertTrue(text.contains("결제 연동 전"));
            assertTrue(text.contains("새로 관측된 경고"));
            assertTrue(text.contains("BRAKE_LOW"));
            assertTrue(text.contains("수집 공백"));
            assertTrue(text.contains("전송 지연"));
            assertTrue(text.contains("전원 꺼짐"));
            assertTrue(text.contains("통신 끊김"));
            assertFalse(text.contains("requestedEndAt"));
            assertFalse(text.contains("CLIENT_REPORTED"));
        }
        var sample=Path.of("build","tmp","reports-qa","fictional-report.pdf");
        Files.createDirectories(sample.getParent());
        Files.write(sample,bytes);
    }
}
