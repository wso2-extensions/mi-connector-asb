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
import io.ballerina.stdlib.mi.utils.SynapseUtils;
import org.apache.synapse.MessageContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Inbound-only operation that records a "deadLetter" settlement decision for the current ASB
 * message, so the listener moves it to the Dead-Letter Queue. An optional reason and error
 * description may be supplied via the {@code deadLetterReason} and
 * {@code deadLetterErrorDescription} template parameters; they are passed to the listener as
 * String properties.
 */
public class InboundDeadLetter extends AbstractInboundSettlement {

    private static final String DEAD_LETTER_REASON = "deadLetterReason";
    private static final String DEAD_LETTER_ERROR_DESCRIPTION = "deadLetterErrorDescription";

    @Override
    protected String getSettlementAction() {
        return Constants.SETTLEMENT_ACTION_DEAD_LETTER;
    }

    @Override
    protected Map<String, String> recordSettlementOptions(MessageContext messageContext) {
        Map<String, String> decision = new HashMap<>();
        String reason = lookup(messageContext, DEAD_LETTER_REASON);
        if (reason != null) {
            decision.put(Constants.DECISION_KEY_DEAD_LETTER_REASON, reason);
        }
        String description = lookup(messageContext, DEAD_LETTER_ERROR_DESCRIPTION);
        if (description != null) {
            decision.put(Constants.DECISION_KEY_DEAD_LETTER_ERROR_DESCRIPTION, description);
        }
        return decision;
    }

    private static String lookup(MessageContext messageContext, String paramName) {
        Object value = SynapseUtils.lookupTemplateParameter(messageContext, paramName);
        if (value == null) {
            return null;
        }
        String str = value.toString();
        return str.isEmpty() ? null : str;
    }
}
