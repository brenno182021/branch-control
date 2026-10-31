package br.com.el.branchcontrol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static br.com.el.branchcontrol.Models.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

class PdfReportApiTest {
    @Test void appliesEnvironmentScopeAndRequiresReportMembershipForIndividualRun() throws Exception {
        var runs=mock(RunService.class); var envs=mock(EnvironmentStore.class);
        var actor=new Actor("u","usuário","USER"); var principal=mock(LocalPrincipal.class);
        var auth=mock(Authentication.class); when(auth.isAuthenticated()).thenReturn(true); when(auth.getPrincipal()).thenReturn(principal); when(principal.actor()).thenReturn(actor);
        var env=new EnvironmentStore.Environment("env","Ambiente","repo",List.of("allowed/*"),List.of("REPORTS"),List.of("u"),true,1);
        when(envs.require(actor,"env",Permissions.REPORTS)).thenReturn(env);
        var c=List.of(new CommitProgress("abc","VISÍVEL","APPLIED","def"));
        var allowed=new RunView("allowed","FAILED",false,"repo","allowed/a","allowed/b","2026-01-01T12:00:00Z","","",c,List.of(),actor,"p");
        var hidden=new RunView("hidden","FAILED",false,"repo","secret/a","allowed/b","2026-01-01T12:00:00Z","","",List.of(new CommitProgress("xyz","SEGREDO","APPLIED","ghi")),List.of(),actor,"p");
        when(runs.reports(actor)).thenReturn(List.of(allowed,hidden));
        var controller=new ReportController(runs,new AppProperties("", "UTC", 30),envs);
        var response=controller.pdf(auth,null,null,null,"env");
        assertEquals("no-store",response.getHeaders().getCacheControl());
        try(var pdf=Loader.loadPDF(response.getBody())) { String text=new PDFTextStripper().getText(pdf); assertTrue(text.contains("VISÍVEL")); assertFalse(text.contains("SEGREDO")); }
        when(runs.reports(actor)).thenReturn(List.of());
        assertEquals(404,assertThrows(AppException.class,()->controller.pdfRun(auth,"allowed")).status());
        assertEquals(401,assertThrows(AppException.class,()->controller.pdf(null,null,null,null,null)).status());
        when(envs.require(actor,"sem-permissao",Permissions.REPORTS)).thenThrow(new AppException(403,"Sem permissão"));
        assertEquals(403,assertThrows(AppException.class,()->controller.pdf(auth,null,null,null,"sem-permissao")).status());
    }
}
