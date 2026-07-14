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

/**
 * Inbound-only operation that records a "defer" settlement decision for the current ASB message.
 * Deferred messages remain in the queue/subscription and can only be retrieved later by sequence
 * number.
 */
public class InboundDefer extends AbstractInboundSettlement {

    @Override
    protected String getSettlementAction() {
        return Constants.SETTLEMENT_ACTION_DEFER;
    }
}
