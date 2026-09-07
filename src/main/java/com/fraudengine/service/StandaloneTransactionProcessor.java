package com.fraudengine.service;

import com.fraudengine.engine.RuleEngine;
import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionStatus;
import com.fraudengine.repository.FraudAssessmentRepository;
import com.fraudengine.repository.TransactionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Persists, evaluates, and marks a single transaction ASSESSED, one Postgres transaction per call.
// A separate bean (not a private method on StandaloneTransactionController) so @Transactional
// actually applies when called in a loop from stream(): Spring's proxy only intercepts calls that
// cross a bean boundary, not same-class ("self-invocation") calls — the same reason
// ReferenceDataCache is a separate bean from EvaluationContextBuilder for its @Cacheable methods.
// Without this, stream() wrapping its whole loop in one @Transactional held a single Postgres
// transaction open for the entire batch — nothing committed until every iteration finished, and
// any single failure (or just running long enough) lost the whole batch with zero partial progress.
@Service
@Profile("standalone | local")
public class StandaloneTransactionProcessor {

    private final TransactionRepository transactionRepository;
    private final FraudAssessmentRepository fraudAssessmentRepository;
    private final RuleEngine ruleEngine;

    public StandaloneTransactionProcessor(TransactionRepository transactionRepository,
                                           FraudAssessmentRepository fraudAssessmentRepository,
                                           RuleEngine ruleEngine) {
        this.transactionRepository = transactionRepository;
        this.fraudAssessmentRepository = fraudAssessmentRepository;
        this.ruleEngine = ruleEngine;
    }

    @Transactional
    public FraudAssessment process(Transaction tx) {
        tx = transactionRepository.save(tx);

        FraudAssessment assessment = ruleEngine.evaluate(tx);
        fraudAssessmentRepository.save(assessment);

        tx.setStatus(TransactionStatus.ASSESSED);
        transactionRepository.save(tx);

        return assessment;
    }
}
