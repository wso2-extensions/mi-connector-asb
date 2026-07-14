/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.mi.settlement;

import io.ballerina.stdlib.mi.Constants;
import io.ballerina.stdlib.mi.PayloadWriter;
import io.ballerina.stdlib.mi.utils.SynapseUtils;
import org.apache.axis2.AxisFault;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.data.connector.DefaultConnectorResponse;
import org.wso2.integration.connector.core.AbstractConnector;
import org.wso2.integration.connector.core.ConnectException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class for the inbound-only ASB settlement operations
 * ({@link InboundComplete}, {@link InboundAbandon}, {@link InboundDefer}, {@link InboundDeadLetter}).
 *
 * <p>These operations record a settlement decision for the message currently being processed in an
 * ASB inbound flow; the inbound listener applies the decision to settle the message. The listener
 * seeds two objects into the message context before injecting the message: a {@link CountDownLatch}
 * under {@link Constants#ASB_INBOUND_SETTLEMENT_LATCH} (which it then waits on) and an {@link AtomicReference}
 * holding an initially empty {@code Map<String, String>} decision holder under
 * {@link Constants#ASB_INBOUND_SETTLEMENT_DECISION}.
 *
 * <p>When invoked, this mediator builds a decision map &mdash; the action under
 * {@link Constants#DECISION_KEY_ACTION} plus any operation-specific options such as the dead-letter
 * reason/description (see {@link #recordSettlementOptions}) &mdash; and publishes it by
 * {@code compareAndSet}ing that new map into the shared {@link AtomicReference} holder. Because the
 * {@code AtomicReference} object itself is seeded before injection, the published value is visible to
 * the listener even when the mediation context is cloned. It then counts the latch down so the
 * listener proceeds and settles the message. Only one settlement decision may be recorded per message.
 */
public abstract class AbstractInboundSettlement extends AbstractConnector {

    @Override
    @SuppressWarnings("unchecked")
    public void connect(MessageContext messageContext) throws ConnectException {
        String action = getSettlementAction();
        try {
            Object latchObj = messageContext.getProperty(Constants.ASB_INBOUND_SETTLEMENT_LATCH);
            Object holderObj = messageContext.getProperty(Constants.ASB_INBOUND_SETTLEMENT_DECISION);
            if (!(latchObj instanceof CountDownLatch latch) || !(holderObj instanceof AtomicReference)) {
                throw new SynapseException("Settlement failed: no ASB inbound settlement handshake is " +
                        "available. Inbound settlement operations are only valid inside an ASB inbound flow.");
            }
            AtomicReference<Map<String, String>> decisionHolder = (AtomicReference<Map<String, String>>) holderObj;

            // Build the decision map: the action plus any operation-specific options (e.g. dead-letter
            // reason). The AtomicReference holder is shared with the listener (seeded before injection),
            // so compareAndSet-ing this new map into it makes the decision visible to the listener even
            // when the mediation context is cloned (the clone copies the same holder reference).
            Map<String, String> decision = recordSettlementOptions(messageContext);
            decision.put(Constants.DECISION_KEY_ACTION, action);

            Map<String, String> current = decisionHolder.get();
            if (current != null && !current.isEmpty()) {
                throw new SynapseException("Settlement failed: a settlement decision ('" +
                        current.get(Constants.DECISION_KEY_ACTION) + "') has already been recorded for this " +
                        "message. Only one settlement operation may be invoked per inbound message.");
            }
            if (!decisionHolder.compareAndSet(current, decision)) {
                throw new SynapseException("Settlement failed: a concurrent settlement decision was recorded for " +
                        "this message. Only one settlement operation may be invoked per inbound message.");
            }
            log.info("Recorded ASB inbound settlement decision '" + action + "'; releasing the listener.");
            latch.countDown();
            setResponse(messageContext);
        } catch (AxisFault | SynapseException e) {
            // handleException logs the (detailed) message and throws a SynapseException, which
            // drives the fault sequence. It never returns, so no further throw is needed here.
            handleException("ASB inbound '" + action + "' settlement failed: " + e.getMessage(), e, messageContext);
        }
    }

    /**
     * Returns the settlement action recorded for the listener
     * (one of the {@code Constants.SETTLEMENT_ACTION_*} values). Also used for logging.
     */
    protected abstract String getSettlementAction();

    /**
     * Hook for operation-specific options. Returns a mutable map to which the base class adds the
     * action. The default has no options; {@code InboundDeadLetter} overrides it to add the
     * dead-letter reason and error description.
     */
    protected Map<String, String> recordSettlementOptions(MessageContext messageContext) {
        return new HashMap<>();
    }

    private void setResponse(MessageContext messageContext) throws AxisFault {
        // Settlement returns no payload; mirror BalExecutor's response handling for consistency.
        if (isOverwriteBody(messageContext)) {
            PayloadWriter.overwriteBody(messageContext, null);
        }
        ConnectorResponse connectorResponse = new DefaultConnectorResponse();
        connectorResponse.setPayload(null);
        Object responseVariable = SynapseUtils.lookupTemplateParameter(messageContext, Constants.RESPONSE_VARIABLE);
        if (responseVariable != null) {
            messageContext.setVariable(responseVariable.toString(), connectorResponse);
        }
    }

    private static boolean isOverwriteBody(MessageContext messageContext) {
        Object overwriteBody = SynapseUtils.lookupTemplateParameter(messageContext, Constants.OVERWRITE_BODY);
        return overwriteBody != null && Boolean.parseBoolean(overwriteBody.toString());
    }
}
