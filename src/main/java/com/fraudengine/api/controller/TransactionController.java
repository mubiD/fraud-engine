package com.fraudengine.api.controller;

import com.fraudengine.api.dto.SubmitTransactionRequest;
import com.fraudengine.api.dto.SubmitTransactionResponse;
import com.fraudengine.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmitTransactionResponse submit(@Valid @RequestBody SubmitTransactionRequest request) {
        UUID transactionId = transactionService.submit(request);
        SubmitTransactionResponse response = new SubmitTransactionResponse();
        response.setTransactionId(transactionId);
        response.setStatus("PENDING");
        response.setMessage("Transaction accepted for fraud evaluation");
        return response;
    }
}
