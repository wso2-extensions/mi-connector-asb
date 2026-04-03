#!/usr/bin/env bash
# =============================================================================
# test-apis.sh — Azure Service Bus (ASB) Connector API Test Script
# WSO2 Micro Integrator project: asb-connector-testing
#
# Usage:
#   chmod +x test-apis.sh
#   ./test-apis.sh                  Run all tests (skips close)
#   ./test-apis.sh topic            Topic CRUD operations
#   ./test-apis.sh queue            Queue CRUD operations
#   ./test-apis.sh subscription     Subscription CRUD operations
#   ./test-apis.sh rule             Rule operations (create/update known connector bug)
#   ./test-apis.sh message          Send & receive messages + schedule + receiver ops
#   ./test-apis.sh receiver         MessageReceiver operations (complete/abandon/defer/deadLetter/renewLock)
#   ./test-apis.sh admin            All admin operations
#   ./test-apis.sh close            Close sender & receiver (DESTRUCTIVE — requires server restart after)
#
# NOTE: 'close' is excluded from 'all' and 'message' runs intentionally.
#       Once close is called, the Ballerina ASB sender/receiver client is
#       permanently disposed until the server is restarted.
# =============================================================================

# ── Configuration ─────────────────────────────────────────────────────────────
BASE_URL="http://localhost:8290"
TOPIC_NAME="test-topic"
SUBSCRIPTION_NAME="test-subscription"
QUEUE_NAME="test-queue"
RULE_NAME="test-rule"
CONTENT_TYPE="Content-Type: application/json"

# Colours
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ── Counters ──────────────────────────────────────────────────────────────────
pass=0
fail=0
skip=0

# ── Helpers ───────────────────────────────────────────────────────────────────
print_header() {
    echo ""
    echo -e "${CYAN}══════════════════════════════════════════${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}══════════════════════════════════════════${NC}"
}

# run_test <name> <method> <url> <data|""> <expected_http>
# Fails if HTTP status != expected OR if response body contains {"error":...}
run_test() {
    local name="$1"
    local method="$2"
    local url="$3"
    local data="$4"
    local expected_http="$5"
    local RESP_FILE
    RESP_FILE=$(mktemp)

    if [ -n "$data" ]; then
        http_code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" \
            -X "$method" "$url" \
            -H "$CONTENT_TYPE" \
            -d "$data")
    else
        http_code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" \
            -X "$method" "$url" \
            -H "$CONTENT_TYPE")
    fi

    body=$(cat "$RESP_FILE")
    rm -f "$RESP_FILE"

    # Detect error body — any response containing top-level "error" key is a failure
    has_error=false
    if echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if 'error' in d else 1)" 2>/dev/null; then
        has_error=true
    fi

    if [ "$http_code" -ne "$expected_http" ] || [ "$has_error" = true ]; then
        echo -e "  ${RED}FAIL${NC} [HTTP $http_code] $name"
        [ "$has_error" = true ] && echo -e "       ${RED}^ Response contains error body${NC}"
        ((fail++))
    else
        echo -e "  ${GREEN}PASS${NC} [HTTP $http_code] $name"
        ((pass++))
    fi

    if [ -n "$body" ] && [ "$body" != "null" ] && [ "$body" != "" ]; then
        echo "$body" | python3 -m json.tool 2>/dev/null | sed 's/^/    /' || echo "    $body"
    fi
    echo ""
}

# skip_test — marks a test as skipped with a reason
skip_test() {
    local name="$1"
    local reason="$2"
    echo -e "  ${YELLOW}SKIP${NC} $name"
    echo -e "       ${YELLOW}^ $reason${NC}"
    echo ""
    ((skip++))
}

# receive_msg_seq — Receives one message and echoes its sequenceNumber (or "" on failure).
receive_msg_seq() {
    local resp
    resp=$(curl -s -X GET "$BASE_URL/messagereceiver/receive" -H "$CONTENT_TYPE")
    echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('sequenceNumber',''))" 2>/dev/null
}

wait_for_server() {
    echo -e "${YELLOW}Waiting for MI server to be ready...${NC}"
    for i in $(seq 1 30); do
        if curl -s --max-time 2 "$BASE_URL/admin/listTopics" > /dev/null 2>&1; then
            echo -e "${GREEN}Server is up!${NC}"
            return 0
        fi
        sleep 2
        echo -n "."
    done
    echo -e "\n${RED}Server did not respond after 60 seconds. Make sure WSO2 MI is running.${NC}"
    exit 1
}

# ── Test Groups ───────────────────────────────────────────────────────────────

test_topics() {
    print_header "TOPIC OPERATIONS"

    run_test "Create Topic" POST "$BASE_URL/admin/createTopic" \
        "{\"topicName\": \"$TOPIC_NAME\"}" 200

    run_test "Topic Exists" GET "$BASE_URL/admin/topicExists/$TOPIC_NAME" \
        "" 200

    run_test "Get Topic" GET "$BASE_URL/admin/getTopic?topicName=$TOPIC_NAME" \
        "" 200

    run_test "List Topics" GET "$BASE_URL/admin/listTopics" \
        "" 200

    run_test "Update Topic" PUT "$BASE_URL/admin/updateTopic" \
        "{\"topicName\": \"$TOPIC_NAME\"}" 200
}

test_subscriptions() {
    print_header "SUBSCRIPTION OPERATIONS"

    # API reads payload.subsName (not subName)
    run_test "Create Subscription" POST "$BASE_URL/admin/createSubscription" \
        "{\"topicName\": \"$TOPIC_NAME\", \"subsName\": \"$SUBSCRIPTION_NAME\"}" 200

    run_test "Subscription Exists" GET \
        "$BASE_URL/admin/subscriptionExists?topicName=$TOPIC_NAME&subName=$SUBSCRIPTION_NAME" \
        "" 200

    run_test "Get Subscription" GET \
        "$BASE_URL/admin/getSubscription?topicName=$TOPIC_NAME&subName=$SUBSCRIPTION_NAME" \
        "" 200

    run_test "List Subscriptions" GET \
        "$BASE_URL/admin/listSubscriptions/$TOPIC_NAME" \
        "" 200

    # API reads payload.subscriptionName, payload.idleSecond, payload.idleNanoSecond
    run_test "Update Subscription" PUT "$BASE_URL/admin/updateSubscription" \
        "{\"topicName\": \"$TOPIC_NAME\", \"subscriptionName\": \"$SUBSCRIPTION_NAME\", \"idleSecond\": 3600, \"idleNanoSecond\": 0}" 200
}

test_rules() {
    print_header "RULE OPERATIONS"

    run_test "Create Rule" POST "$BASE_URL/admin/createRule" \
        "{\"topicName\": \"$TOPIC_NAME\", \"subName\": \"$SUBSCRIPTION_NAME\", \"ruleName\": \"$RULE_NAME\", \"filter\": \"Region = 'Europe'\", \"action\": \"SET Priority = 'High'\"}" 200

    run_test "Get Rule" GET \
        "$BASE_URL/admin/getRule?topicName=$TOPIC_NAME&subName=$SUBSCRIPTION_NAME&ruleName=$RULE_NAME" \
        "" 200

    run_test "Update Rule" PUT "$BASE_URL/admin/updateRule" \
        "{\"topicName\": \"$TOPIC_NAME\", \"subName\": \"$SUBSCRIPTION_NAME\", \"ruleName\": \"$RULE_NAME\", \"filter\": \"Region = 'EMEA'\", \"action\": \"SET Priority = 'Urgent'\"}" 200

    # List Rules (read-only, no filter param)
    run_test "List Rules" GET \
        "$BASE_URL/admin/listRules?topicName=$TOPIC_NAME&subName=$SUBSCRIPTION_NAME" \
        "" 200

    run_test "Delete Rule" DELETE \
        "$BASE_URL/admin/deleteRule?topicName=$TOPIC_NAME&subName=$SUBSCRIPTION_NAME&ruleName=$RULE_NAME" \
        "" 200
}

test_queues() {
    print_header "QUEUE OPERATIONS"

    run_test "Create Queue" POST "$BASE_URL/admin/createQueue" \
        "{\"queueName\": \"$QUEUE_NAME\"}" 200

    run_test "Queue Exists" GET "$BASE_URL/admin/queueExists/$QUEUE_NAME" \
        "" 200

    run_test "Get Queue" GET "$BASE_URL/admin/getQueue/$QUEUE_NAME" \
        "" 200

    run_test "List Queues" GET "$BASE_URL/admin/listQueues" \
        "" 200

    # API reads payload.queueName and payload.doiSeconds (autoDeleteOnIdle seconds)
    run_test "Update Queue" PUT "$BASE_URL/admin/updateQueue" \
        "{\"queueName\": \"$QUEUE_NAME\", \"doiSeconds\": 3600}" 200
}

test_messages() {
    print_header "MESSAGE SEND OPERATIONS"

    # /send — API reads payload.body
    run_test "Send Message" POST "$BASE_URL/messagesender/send" \
        "{\"body\": \"Hello from test-apis.sh\"}" 200

    # /sendPayload — API reads payload.messagePayload
    run_test "Send Payload" POST "$BASE_URL/messagesender/sendPayload" \
        "{\"messagePayload\": \"{\\\"event\\\": \\\"test\\\", \\\"value\\\": 42}\"}" 200

    # /sendBatch — API reads payload.count and payload.batch (JSON array)
    run_test "Send Batch" POST "$BASE_URL/messagesender/sendBatch" \
        "{\"count\": 2, \"batch\": [{\"body\": \"Batch msg 1\"}, {\"body\": \"Batch msg 2\"}]}" 200

    print_header "MESSAGE RECEIVE OPERATIONS"

    run_test "Receive Message" GET "$BASE_URL/messagereceiver/receive" \
        "" 200

    run_test "Receive Payload Only" GET "$BASE_URL/messagereceiver/receivePayload" \
        "" 200

    run_test "Receive Batch (count=3)" GET "$BASE_URL/messagereceiver/receiveBatch/3" \
        "" 200
}

test_schedule() {
    print_header "SCHEDULE & CANCEL MESSAGE"

    # API reads: body plus individual date fields: year, month, day, hour, minute, second
    echo -e "  ${CYAN}Scheduling message...${NC}"
    SCHED_FILE=$(mktemp)
    SCHED_HTTP=$(curl -s -o "$SCHED_FILE" -w "%{http_code}" \
        -X POST "$BASE_URL/messagesender/schedule" \
        -H "$CONTENT_TYPE" \
        -d '{
            "body": "Scheduled message from test-apis.sh",
            "year": "2099", "month": "01", "day": "26",
            "hour": "10", "minute": "30", "second": "00"
        }')
    SCHED_RESP=$(cat "$SCHED_FILE")
    rm -f "$SCHED_FILE"
    echo "$SCHED_RESP" | python3 -m json.tool 2>/dev/null | sed 's/^/    /' || echo "    $SCHED_RESP"

    has_sched_error=false
    if echo "$SCHED_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if 'error' in d else 1)" 2>/dev/null; then
        has_sched_error=true
    fi

    if [ "$SCHED_HTTP" -ne 200 ] || [ "$has_sched_error" = true ]; then
        echo -e "  ${RED}FAIL${NC} [HTTP $SCHED_HTTP] Schedule Message"
        [ "$has_sched_error" = true ] && echo -e "       ${RED}^ Response contains error body${NC}"
        ((fail++))
        echo ""
    else
        echo -e "  ${GREEN}PASS${NC} [HTTP $SCHED_HTTP] Schedule Message"
        ((pass++))
        echo ""

        SEQ_NUMBER=$(echo "$SCHED_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('sequenceNumber',''))" 2>/dev/null)
        if [ -n "$SEQ_NUMBER" ]; then
            run_test "Cancel Scheduled Message (seq=$SEQ_NUMBER)" GET \
                "$BASE_URL/messagesender/cancel/$SEQ_NUMBER" \
                "" 200
        else
            echo -e "  ${YELLOW}Could not extract sequenceNumber — skipping cancel test${NC}"
            echo ""
        fi
    fi
}

# ── MessageReceiver Operations (require PEEK_LOCK mode) ───────────────────────
#
# Each sub-test sends a message first so there is something to receive.
# The /complete, /abandon, /deadLetter, and /renewLock endpoints now perform
# receive internally — no manual sequenceNumber passing is required.
# Tests are ordered to avoid lock conflicts:
#   renewLock   (receive → renew lock)
#   complete    (receive → complete)
#   abandon     (receive → abandon; returns message to queue)
#   deadLetter  (receive → dead-letter; reason/description via query params)
#   defer → receiveDeferred  (skipped — known connector bugs)

test_receiver_ops() {
    # ── renewLock ────────────────────────────────────────────────────────────
    print_header "RECEIVER OPERATIONS — receiveAndRenewLock"

    run_test "Send message (for receiveAndRenewLock)" POST "$BASE_URL/messagesender/send" \
        '{"body": "test for renewLock"}' 200

    run_test "Receive and Renew Lock" GET "$BASE_URL/messagereceiver/receiveAndRenewLock" \
        "" 200

    # ── complete ─────────────────────────────────────────────────────────────
    print_header "RECEIVER OPERATIONS — receiveAndComplete"

    run_test "Send message (for receiveAndComplete)" POST "$BASE_URL/messagesender/send" \
        '{"body": "test for complete"}' 200

    run_test "Receive and Complete" GET "$BASE_URL/messagereceiver/receiveAndComplete" \
        "" 200

    # ── abandon ──────────────────────────────────────────────────────────────
    print_header "RECEIVER OPERATIONS — receiveAndAbandon"

    run_test "Send message (for receiveAndAbandon)" POST "$BASE_URL/messagesender/send" \
        '{"body": "test for abandon"}' 200

    run_test "Receive and Abandon" GET "$BASE_URL/messagereceiver/receiveAndAbandon" \
        "" 200

    # ── receiveAndDefer ───────────────────────────────────────────────────────
    print_header "RECEIVER OPERATIONS — receiveAndDefer"

    run_test "Send message (for receiveAndDefer)" POST "$BASE_URL/messagesender/send" \
        '{"body": "test for defer"}' 200

    echo -e "  ${CYAN}Receiving and deferring message — capturing sequenceNumber for receiveDeferred...${NC}"
    DEFER_FILE=$(mktemp)
    DEFER_HTTP=$(curl -s -o "$DEFER_FILE" -w "%{http_code}" -X GET "$BASE_URL/messagereceiver/receiveAndDefer" -H "$CONTENT_TYPE")
    DEFER_RESP=$(cat "$DEFER_FILE")
    rm -f "$DEFER_FILE"

    if [ "$DEFER_HTTP" -ne 200 ]; then
        echo -e "  ${RED}FAIL${NC} [HTTP $DEFER_HTTP] Receive and Defer"
        ((fail++))
        echo ""
        echo -e "  ${RED}FAIL${NC} Receive Deferred and Complete"
        echo -e "       ${RED}^ receiveAndDefer failed — no deferred sequenceNumber available${NC}"
        ((fail++))
        echo ""
    else
        echo -e "  ${GREEN}PASS${NC} [HTTP $DEFER_HTTP] Receive and Defer"
        ((pass++))
        echo "$DEFER_RESP" | python3 -m json.tool 2>/dev/null | sed 's/^/    /' || echo "    $DEFER_RESP"
        echo ""

        DEFERRED_SEQ=$(echo "$DEFER_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('sequenceNumber',''))" 2>/dev/null)
        if [ -n "$DEFERRED_SEQ" ]; then
            run_test "Receive Deferred and Complete (seq=$DEFERRED_SEQ)" POST \
                "$BASE_URL/messagereceiver/receiveDeferred" \
                "{\"id\": $DEFERRED_SEQ}" 200
        else
            echo -e "  ${RED}FAIL${NC} Receive Deferred and Complete"
            echo -e "       ${RED}^ could not extract sequenceNumber from defer response: $DEFER_RESP${NC}"
            ((fail++))
            echo ""
        fi
    fi

    # ── deadLetter ────────────────────────────────────────────────────────────
    print_header "RECEIVER OPERATIONS — receiveAndDeadLetter"

    run_test "Send message (for receiveAndDeadLetter)" POST "$BASE_URL/messagesender/send" \
        '{"body": "test for deadLetter"}' 200

    run_test "Receive and Dead Letter" GET "$BASE_URL/messagereceiver/receiveAndDeadLetter" \
        "" 200

    # ── receiveBatchAndSettle ─────────────────────────────────────────────────
    print_header "RECEIVER OPERATIONS — receiveBatchAndSettle"

    run_test "Send message 1 (for receiveBatchAndSettle)" POST "$BASE_URL/messagesender/send" \
        '{"body": "batch settle msg 1"}' 200
    run_test "Send message 2 (for receiveBatchAndSettle)" POST "$BASE_URL/messagesender/send" \
        '{"body": "batch settle msg 2"}' 200

    run_test "Receive Batch and Settle" GET "$BASE_URL/messagereceiver/receiveBatchAndSettle" \
        "" 200
}

close_connections() {
    print_header "CLOSE CONNECTIONS"
    echo -e "  ${YELLOW}WARNING: Closing connections is DESTRUCTIVE.${NC}"
    echo -e "  ${YELLOW}After close, the sender/receiver cannot be reused until the server is restarted.${NC}"
    echo ""
    run_test "Close Sender" GET "$BASE_URL/messagesender/close" "" 200
    run_test "Close Receiver" GET "$BASE_URL/messagereceiver/close" "" 200
    echo -e "  ${YELLOW}=> Restart the WSO2 MI server before running tests again.${NC}"
    echo ""
}

test_admin_all() {
    test_topics
    test_subscriptions
    test_rules
    test_queues

    print_header "CLEANUP"
    run_test "Delete Subscription" DELETE \
        "$BASE_URL/admin/deleteSubscription?topicName=$TOPIC_NAME&subName=$SUBSCRIPTION_NAME" \
        "" 200

    run_test "Delete Topic" DELETE \
        "$BASE_URL/admin/deleteTopic/$TOPIC_NAME" \
        "" 200

    run_test "Delete Queue" DELETE \
        "$BASE_URL/admin/deleteQueue?queueName=$QUEUE_NAME" \
        "" 200
}

print_summary() {
    echo ""
    echo -e "${CYAN}══════════════════════════════════════════${NC}"
    echo -e "${CYAN}  TEST SUMMARY${NC}"
    echo -e "${CYAN}══════════════════════════════════════════${NC}"
    echo -e "  ${GREEN}Passed: $pass${NC}"
    echo -e "  ${RED}Failed: $fail${NC}"
    echo -e "  ${YELLOW}Skipped: $skip${NC}"
    total=$((pass + fail + skip))
    echo -e "  Total:  $total"
    echo ""
    if [ "$fail" -eq 0 ]; then
        echo -e "  ${GREEN}All executable tests passed!${NC}"
        [ "$skip" -gt 0 ] && echo -e "  ${YELLOW}$skip test(s) skipped${NC}"
    else
        echo -e "  ${RED}$fail test(s) failed. Review the output above for error details.${NC}"
        exit 1
    fi
    echo ""
}

# ── Entry Point ───────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  ASB Connector — WSO2 MI API Test Runner     ║${NC}"
echo -e "${CYAN}║  Base URL: $BASE_URL             ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════╝${NC}"

if ! command -v curl &> /dev/null; then
    echo -e "${RED}Error: curl is not installed.${NC}"
    exit 1
fi
if ! command -v python3 &> /dev/null; then
    echo -e "${RED}Error: python3 is not installed.${NC}"
    exit 1
fi

GROUP="${1:-all}"

# Note: 'all' runs all operations including close (requires server restart after).
case "$GROUP" in
    topic)        wait_for_server; test_topics ;;
    subscription) wait_for_server; test_subscriptions ;;
    rule)         wait_for_server; test_rules ;;
    queue)        wait_for_server; test_queues ;;
    message)      wait_for_server; test_messages; test_schedule; test_receiver_ops ;;
    receiver)     wait_for_server; test_receiver_ops ;;
    admin)        wait_for_server; test_admin_all ;;
    close)        wait_for_server; close_connections ;;
    all|"")       wait_for_server; test_admin_all; test_messages; test_schedule; test_receiver_ops ;;
    *)
        echo -e "${RED}Unknown group: $GROUP${NC}"
        echo "Valid groups: topic, subscription, rule, queue, message, receiver, admin, close, all"
        exit 1
        ;;
esac

print_summary
