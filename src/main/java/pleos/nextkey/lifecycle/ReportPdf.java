package pleos.nextkey.lifecycle;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pleos.nextkey.common.ApiError;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.text.NumberFormat;

@Component
class ReportPdf {
    private final Path fontFile;
    ReportPdf(@Value("${nextkey.pdf.font:${NEXTKEY_PDF_FONT:}}") String configured) {
        this.fontFile=Path.of(configured.isBlank()?"C:/Windows/Fonts/malgun.ttf":configured);
    }

    byte[] render(Map<String,Object> report) {
        if (!Files.isRegularFile(fontFile)) throw new ApiError(503,"PDF_FONT_UNAVAILABLE");
        try (var document=new PDDocument(); var out=new ByteArrayOutputStream()) {
            var font=PDType0Font.load(document,fontFile.toFile());
            try (var page=new Layout(document,font)) {
                page.title("개인 이용 보고서");
                if (containsDemo(report)) page.text("가상 시연 데이터 포함",0,11);
                page.text("세션  "+report.get("sessionId"),0,10);
                page.text("예약  "+report.get("reservationId"),0,10);
                var v=asMap(report.get("vehicle"));
                page.section("이용 요약");
                page.text("차량  "+v.get("model")+"  /  "+v.get("id"),0,11);
                page.text("이용 시작 (UTC)  "+report.get("startedAt"),0,11);
                page.text("이용 종료 (UTC)  "+report.get("endedAt"),0,11);
                page.text("서버 수신 (UTC)  "+report.get("endReceivedAt"),0,10);
                page.text("확인된 이용 거리  "+metric(report.get("distanceKm")),0,12);
                page.text("잔량 변화 (종료 - 시작)  "+metric(report.get("energyChange")),0,11);
                page.text("확정 반납 예정 (UTC)  "+report.get("confirmedEndAt"),0,11);
                page.text("시간 초과  "+(Boolean.TRUE.equals(report.get("overdue"))?report.get("overdueSeconds")+"초":"없음"),0,11);
                page.text("계약상 반납  "+("CONFIRMED".equals(report.get("returnState"))?"업체 확인":"미확인"),0,11);
                page.section("시작과 종료 시점의 차량 상태");
                page.snapshots(asMap(report.get("startSnapshot")),asMap(report.get("endSnapshot")));
                page.section("지원 경고의 변화");
                page.fields(report.get("warningChange"),0);
                page.section("최종 안전 상태");
                var safety=asMap(report.get("finalSafety"));
                page.text("주차  "+display(safety.get("parked"))+"   ·   조작 허용  "+display(safety.get("interactionAllowed")),0,11);
                page.text("수집  "+display(safety.get("collectedAt"))+"   ·   "+display(safety.get("source"))+" (차량 앱 보고)",0,10);
                page.section("종료 시 지원 설정");
                page.fields(report.get("finalSettings"),0);
                page.section("제보 · 개인 설정 · 주행 기록");
                page.fields(report.get("records"),0);
                page.section("수집 공백");
                page.fields(report.get("dataGaps"),0);
                page.section("전송 지연");
                page.fields(report.get("syncDelays"),0);
                page.section("연장 및 요금 동의");
                page.extensions(report.get("extensions"));
                page.text("보고서 생성 (UTC)  "+report.get("generatedAt"),0,9);
            }
            document.save(out);
            return out.toByteArray();
        } catch(IOException | IllegalArgumentException ex) {
            throw new ApiError(503,"PDF_RENDER_FAILED");
        }
    }
    private static boolean containsDemo(Object item) {
        if (item instanceof Map<?,?> map) return map.values().stream().anyMatch(ReportPdf::containsDemo);
        if (item instanceof List<?> list) return list.stream().anyMatch(ReportPdf::containsDemo);
        return "DEV_SIMULATOR".equals(item);
    }
    private static Map<?,?> asMap(Object value) { return value instanceof Map<?,?> map?map:Map.of(); }
    private static String metric(Object obj) {
        var m=asMap(obj);
        return "VALID".equals(m.get("status")) ? number(m.get("value"))+" "+m.get("unit") : display(m.get("status"));
    }
    private static final ResourceBundle WORDS=ResourceBundle.getBundle("pleos.nextkey.lifecycle.report",Locale.ROOT);
    private static final Set<String> INTERNAL_KEYS=Set.of("id","session_id","consent_id","report_id");
    private static String translated(String key,String fallback) {
        return WORDS.containsKey(key)?WORDS.getString(key):fallback;
    }
    private static String label(Object key) {
        String name=key.toString();
        return translated("field."+name.replace("_","").toLowerCase(Locale.ROOT),name);
    }
    private static String display(Object value) {
        if (value==null) return "미확인";
        if (value instanceof Boolean flag) return flag?"예":"아니요";
        String text=value.toString();
        return translated("enum."+text,text);
    }
    private static String number(Object value) {
        if (!(value instanceof Number n)) return display(value);
        var fmt=NumberFormat.getNumberInstance(Locale.KOREA);
        fmt.setMaximumFractionDigits(12);
        return fmt.format(n);
    }

    private static final class Layout implements AutoCloseable {
        final PDDocument doc;
        final PDFont font;
        PDPageContentStream stream;
        float y;
        Layout(PDDocument doc,PDFont font) throws IOException { this.doc=doc;this.font=font;newPage(); }
        void newPage() throws IOException {
            if (stream!=null) stream.close();
            var p=new PDPage(PDRectangle.A4);
            doc.addPage(p);
            stream=new PDPageContentStream(doc,p);
            draw("NEXTKEY",44,799,13);
            draw("개인 이용 내역    /    "+doc.getNumberOfPages(),44,30,9);
            y=765;
        }
        void title(String text) throws IOException { text(text,0,22); y-=5; }
        void section(String text) throws IOException {
            if (y<110) newPage();
            y-=9;
            text(text,0,15);
        }
        void snapshots(Map<?,?> start,Map<?,?> end) throws IOException {
            for (String key:List.of("odometer","battery","fuel","warnings")) {
                var a=asMap(start.get(key));
                var b=asMap(end.get(key));
                if ("NOT_APPLICABLE".equals(a.get("status")) && "NOT_APPLICABLE".equals(b.get("status"))) {
                    text(label(key)+"   해당 차종 미지원",0,10);
                    continue;
                }
                text(label(key)+"   시작 "+reading(a)+"   /   종료 "+reading(b),0,11);
                if(a.get("collectedAt")!=null) text("시작 수집  "+a.get("collectedAt")+" · "+display(a.get("source")),1,9);
                if(b.get("collectedAt")!=null) text("종료 수집  "+b.get("collectedAt")+" · "+display(b.get("source")),1,9);
            }
        }
        String reading(Map<?,?> value) {
            if (!"VALID".equals(value.get("status"))) return display(value.get("status"));
            if (value.get("codes") instanceof List<?> codes)
                return codes.isEmpty()?"경고 없음":String.join(", ",codes.stream().map(Object::toString).toList());
            return metric(value);
        }
        void extensions(Object value) throws IOException {
            if (!(value instanceof List<?> entries) || entries.isEmpty()) { text("연장 신청 없음",0,10);return; }
            for(Object entry:entries) {
                var ext=asMap(entry);
                text("신청 결과   "+display(ext.get("status")),0,12);
                text("신청  "+display(ext.get("requestedAt"))+"   ·   기존 종료  "+display(ext.get("previousEndAt")),1,10);
                text("희망 종료  "+display(ext.get("requestedEndAt")),1,10);
                if (ext.get("amount")!=null) text("안내 금액  "+number(ext.get("amount"))+" "+display(ext.get("currency"))+
                        "   ·   안내  "+display(ext.get("quotedAt")),1,10);
                text("요금 동의  "+display(ext.get("feeConsent"))+
                        (ext.get("agreedAt")==null?"":"   ·   "+ext.get("agreedAt")),1,10);
                text("업체 결정  "+display(ext.get("operatorDecision"))+
                        (ext.get("decidedAt")==null?"":"   ·   "+ext.get("decidedAt")),1,10);
                if(ext.get("decisionNote")!=null) text("업체 메모  "+ext.get("decisionNote"),1,10);
                text("결제  "+display(ext.get("payment")),1,10);
            }
        }
        void fields(Object item,int depth) throws IOException {
            if (item instanceof Map<?,?> map) {
                if (map.isEmpty()) text("기록 없음",depth,10);
                for (var e:map.entrySet()) {
                    if (INTERNAL_KEYS.contains(e.getKey())) continue;
                    String key=label(e.getKey());
                    Object v=e.getValue();
                    if ((v instanceof Map<?,?> m && m.isEmpty()) || (v instanceof List<?> l && l.isEmpty())) {
                        text(key+"   기록 없음",depth,10);
                    } else if (v instanceof List<?> l && l.stream().noneMatch(x->x instanceof List<?> || x instanceof Map<?,?>)) {
                        text(key+"   "+String.join(", ",l.stream().map(ReportPdf::display).toList()),depth,10);
                    } else if (v instanceof Map<?,?> || v instanceof List<?>) {
                        text(key,depth,11);
                        fields(v,depth+1);
                    } else text(key+"   "+display(v),depth,10);
                }
            } else if (item instanceof List<?> list) {
                if (list.isEmpty()) text("기록 없음",depth,10);
                int i=0;
                for(Object v:list) {
                    if (list.size()>1 && v instanceof Map<?,?>) text("["+ ++i +"]",depth,10);
                    fields(v,depth);
                }
            } else text(item==null?"미확인":item.toString(),depth,10);
        }
        void text(String raw,int depth,float size) throws IOException {
            float x=44+Math.min(depth,8)*12;
            float width=PDRectangle.A4.getWidth()-44-x;
            var line=new StringBuilder();
            float used=0;
            for(int cp:raw.codePoints().toArray()) {
                String ch=Character.isISOControl(cp)?" ":Character.toString(cp);
                float w;
                try { w=font.getStringWidth(ch)*size/1000; }
                catch (IllegalArgumentException missing) {
                    ch="[U+"+Integer.toHexString(cp).toUpperCase(Locale.ROOT)+"]";
                    w=font.getStringWidth(ch)*size/1000;
                }
                if (used+w>width && !line.isEmpty()) { line(line.toString(),x,size);line.setLength(0);used=0; }
                line.append(ch);used+=w;
            }
            line(line.toString(),x,size);
        }
        void line(String text,float x,float size) throws IOException {
            if (y<56) newPage();
            draw(text,x,y,size);
            y-=size+5;
        }
        void draw(String text,float x,float position,float size) throws IOException {
            stream.beginText();
            stream.setFont(font,size);
            stream.newLineAtOffset(x,position);
            stream.showText(text);
            stream.endText();
        }
        @Override public void close() throws IOException { if (stream!=null) stream.close(); }
    }
}
