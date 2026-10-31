package br.com.el.branchcontrol;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import static br.com.el.branchcontrol.Models.*;

/** Renders self-contained reports with an embedded, Unicode-capable font. */
final class AppliedCommitsPdf {
    private final ZoneId zone;
    AppliedCommitsPdf(ZoneId zone) { this.zone = zone; }
    byte[] render(List<RunView> runs, String filters) throws IOException {
        try (var doc = new PDDocument(); var fontStream = getClass().getResourceAsStream("/fonts/NotoSans-Regular.ttf"); var out = new ByteArrayOutputStream()) {
            if (fontStream == null) throw new IOException("Fonte do relatório não encontrada.");
            var font = PDType0Font.load(doc, fontStream);
            var writer = new Writer(doc, font);
            writer.line("Relatório de commits aplicados", 15);
            writer.line("Gerado em: " + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z").format(ZonedDateTime.now(zone)), 9);
            writer.line("Filtros: " + filters, 9);
            long count = runs.stream().filter(r -> !r.simulation()).flatMap(r -> r.commits().stream()).filter(c -> "APPLIED".equals(c.state())).count();
            writer.line("Total de commits aplicados: " + count, 11);
            for (RunView run : runs) {
                if (run.simulation()) continue;
                var applied = run.commits().stream().filter(c -> "APPLIED".equals(c.state())).toList();
                if (applied.isEmpty()) continue;
                writer.space(9);
                writer.line("Execução: " + run.id() + " | Resultado: " + run.state(), 11);
                writer.line("Data: " + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z").format(Instant.parse(run.startedAt()).atZone(zone)) + " | Usuário: " + run.actor().username(), 9);
                writer.line("Ambiente: " + run.environmentName() + " | Origem: " + run.sourceBranch() + " | Destino: " + run.targetBranch(), 9);
                for (var c : applied) {
                    writer.space(5);
                    writer.line("Commit: " + c.message(), 10);
                    writer.line("Hash original: " + c.hash(), 9);
                    writer.line("Hash aplicado: " + c.newHash(), 9);
                }
            }
            writer.close(); doc.save(out); return out.toByteArray();
        }
    }
    private static final class Writer implements AutoCloseable {
        final PDDocument doc; final PDType0Font font; PDPageContentStream stream; float y; int page;
        Writer(PDDocument doc, PDType0Font font) throws IOException { this.doc=doc; this.font=font; newPage(); }
        void newPage() throws IOException {
            if (stream != null) stream.close();
            var p = new PDPage(PDRectangle.A4); doc.addPage(p); stream = new PDPageContentStream(doc,p); page++;
            y=800; draw("Branch Control", 14, 45, y); draw("Página " + page, 9, 495, y); y-=30;
        }
        void draw(String s, float size, float x, float baseline) throws IOException {
            stream.beginText(); stream.setFont(font,size); stream.setNonStrokingColor(22/255f,72/255f,54/255f); stream.newLineAtOffset(x,baseline); stream.showText(s); stream.endText();
        }
        String safe(String value) {
            var b=new StringBuilder();
            value.codePoints().forEach(cp -> { String c=new String(Character.toChars(cp)); try { font.encode(c); b.append(c); } catch (IllegalArgumentException | IOException ex) { b.append(String.format("U+%04X",cp)); } });
            return b.toString();
        }
        void line(String value, float size) throws IOException {
            String text=safe(value == null ? "" : value).replace('\r',' ').replace('\n',' ');
            StringBuilder part=new StringBuilder();
            for (int offset=0; offset<text.length();) {
                int cp=text.codePointAt(offset); String c=new String(Character.toChars(cp)); offset+=Character.charCount(cp);
                if (font.getStringWidth(part.toString()+c)/1000*size > 505 && part.length()>0) { output(part.toString(),size); part.setLength(0); }
                part.append(c);
            }
            output(part.toString(),size);
        }
        void space(float points) { y -= points; }
        void output(String s,float size) throws IOException { if(y<55) newPage(); draw(s,size,45,y); y-=size+5; }
        public void close() throws IOException { if(stream!=null) stream.close(); }
    }
}
