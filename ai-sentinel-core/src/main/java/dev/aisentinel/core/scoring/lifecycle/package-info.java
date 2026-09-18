/**
 * Model/scorer lifecycle governance: champion/challenger designation, objective
 * comparison evidence, explicit eligibility, approval, promotion, and rollback.
 * <p>
 * {@code METRIC DELTA != GOVERNANCE DECISION}<br>
 * {@code ELIGIBLE != APPROVED}<br>
 * {@code APPROVED != PROMOTED}<br>
 * {@code PROMOTED != PRODUCTION DEPLOYED}<br>
 * {@code CHAMPION DESIGNATION != PRODUCTION SCORER WIRING}<br>
 * {@code ROLLBACK != PRODUCTION DEPLOYMENT ROLLBACK}<br>
 * {@code ACCEPTANCE != AUTOMATIC PROMOTION}<br>
 * {@code SHADOW RESULT != PROMOTION DECISION}<br>
 * {@code SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY}
 * <p>
 * This package never rewires {@code SentinelDecisionEngine}, policy, or
 * enforcement. Promotion updates lifecycle designation state only.
 */
package dev.aisentinel.core.scoring.lifecycle;
