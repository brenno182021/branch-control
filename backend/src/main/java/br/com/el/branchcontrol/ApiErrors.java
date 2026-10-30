package br.com.el.branchcontrol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import java.sql.SQLException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, Object>> application(AppException ex) {
        LOG.warn("Operação recusada: {}", ex.getMessage());
        return ResponseEntity.status(ex.status()).body(Map.of("message", ex.getMessage(), "status", ex.status()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("message", "Confira os campos obrigatórios e a data no formato AAAA-MM-DD.", "status", 400));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> database(DataAccessException ex) {
        String sqlState="UNKNOWN";
        Throwable cause=ex;
        for(int depth=0;cause!=null && depth<20;depth++,cause=cause.getCause()) {
            if(cause instanceof SQLException sql && sql.getSQLState()!=null && sql.getSQLState().matches("[0-9A-Z]{5}")) {
                sqlState=sql.getSQLState(); break;
            }
        }
        // Database exception text can include the entire failing row, including password hashes.
        LOG.error("Falha no banco de dados: tipo={}, SQLSTATE={}",ex.getClass().getSimpleName(),sqlState);
        return ResponseEntity.internalServerError().body(Map.of("message", "Não foi possível concluir a operação no banco de dados.", "status", 500));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        LOG.error("Falha inesperada", ex);
        return ResponseEntity.internalServerError().body(Map.of("message", "Ocorreu uma falha. Consulte o log da aplicação e confira git status.", "status", 500));
    }
}
