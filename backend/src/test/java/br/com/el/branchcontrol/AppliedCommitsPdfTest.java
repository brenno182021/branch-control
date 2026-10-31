package br.com.el.branchcontrol;

import static org.junit.jupiter.api.Assertions.*;
import static br.com.el.branchcontrol.Models.*;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

class AppliedCommitsPdfTest {
    @Test void includesOnlyAppliedRealCommitsAndPreservesLongText() throws Exception {
        String title = "Correção de integração " + "nome-longo ".repeat(600);
        String hash = "a".repeat(40);
        var actor = new Actor("1", "usuário", "ADM");
        var commits = List.of(new CommitProgress(hash, title, "APPLIED", "b".repeat(40)), new CommitProgress("pending", "NÃO INCLUIR", "PENDING", ""));
        var failed = new RunView("1", "FAILED", false, "repo", "origem", "destino", "2026-01-01T12:00:00Z", "", "", commits, List.of(), actor, "p");
        var simulation = new RunView("2", "COMPLETED", true, "repo", "origem", "destino", "2026-01-01T12:00:00Z", "", "", commits, List.of(), actor, "p");
        byte[] pdf = new AppliedCommitsPdf(ZoneId.of("America/Sao_Paulo")).render(List.of(failed, simulation), "Todos");
        try (var doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(doc.getNumberOfPages() > 1);
            assertTrue(text.contains("Correção de integração"));
            assertTrue(text.contains(hash));
            assertFalse(text.contains("NÃO INCLUIR"));
            assertTrue(text.contains("Total de commits aplicados: 1"));
        }
    }
    @Test void emptyReportIsValid() throws Exception {
        try (var doc = Loader.loadPDF(new AppliedCommitsPdf(ZoneId.of("UTC")).render(List.of(), "Todos"))) {
            assertTrue(new PDFTextStripper().getText(doc).contains("Total de commits aplicados: 0"));
        }
    }
    @Test void unsupportedGlyphIsWrittenAsCodePoint() throws Exception {
        var run = new RunView("1", "FAILED", false, "repo", "a", "b", "2026-01-01T12:00:00Z", "", "", List.of(new CommitProgress("a", "Emoji 😀", "APPLIED", "b")), List.of(), new Actor("1", "usuário", "ADM"), "p");
        try (var doc = Loader.loadPDF(new AppliedCommitsPdf(ZoneId.of("UTC")).render(List.of(run), "Todos"))) {
            assertTrue(new PDFTextStripper().getText(doc).contains("U+1F600"));
        }
    }
    @Test void representativeSampleWhenRequested() throws Exception {
        String sample = System.getProperty("branch-control.pdf.sample");
        if (sample == null) return;
        var actor = new Actor("1", "Mariana Costa", "USER");
        var first = new RunView("execucao-2026-001", "FAILED", false, "repositorio", "feature/relatorios", "release/2026-10-30", "2026-10-30T15:20:00Z", "", "", List.of(
            new CommitProgress("a1b2c3d4".repeat(5), "Corrige o cálculo de arrecadação por exercício", "APPLIED", "1a2b3c4d".repeat(5)),
            new CommitProgress("b1c2d3e4".repeat(5), "Inclui validação de permissões para consulta dos relatórios de execução e preserva os filtros selecionados pelo usuário", "APPLIED", "2b3c4d5e".repeat(5)),
            new CommitProgress("c1d2e3f4".repeat(5), "Alteração pendente", "PENDING", "")), List.of(), actor, "p", "1", "Homologação");
        var second = new RunView("execucao-2026-002", "COMPLETED", false, "repositorio", "feature/acessibilidade", "release/2026-10-30", "2026-10-30T18:15:00Z", "", "", java.util.stream.IntStream.range(0, 18).mapToObj(i -> new CommitProgress("d1e2f3a4".repeat(5), "Aprimora a apresentação das informações e a navegação acessível na tela de acompanhamento — etapa " + (i+1), "APPLIED", "4d3c2b1a".repeat(5))).toList(), List.of(), actor, "p", "2", "Produção");
        byte[] bytes = new AppliedCommitsPdf(ZoneId.of("America/Sao_Paulo")).render(List.of(first,second), "Período: outubro de 2026 | Todos os resultados");
        java.nio.file.Files.write(java.nio.file.Path.of(sample), bytes);
        try (var doc = Loader.loadPDF(bytes)) {
            var renderer = new org.apache.pdfbox.rendering.PDFRenderer(doc);
            javax.imageio.ImageIO.write(renderer.renderImageWithDPI(0, 100), "png", java.nio.file.Path.of(sample.replace(".pdf", "-page-1.png")).toFile());
            javax.imageio.ImageIO.write(renderer.renderImageWithDPI(doc.getNumberOfPages()-1, 100), "png", java.nio.file.Path.of(sample.replace(".pdf", "-page-last.png")).toFile());
        }
    }
}
