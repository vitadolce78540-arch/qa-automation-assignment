"""LLM 없이 판정하는 부분의 단위 테스트: 변경점, 정책 검색·인용, 검증기, 영향 분석, 에이전트 가드레일, 코드 게이트."""
from pathlib import Path

import pytest

from req_ai import code_checker, impact, policy, runner, verifier
from req_ai.agent import AgentConfig, TestAgent
from req_ai.device import FakeDevice
from req_ai.fake_driver import FakeDriver
from req_ai.spec_diff import diff_specs, split_sentences

ROOT = Path(__file__).resolve().parent.parent
S = ROOT / "samples"
V1 = (S / "spec" / "order_status_v1.md").read_text(encoding="utf-8")
V2 = (S / "spec" / "order_status_v2.md").read_text(encoding="utf-8")
CLAUSES = policy.load_policy((S / "policy" / "order_policy.md").read_text(encoding="utf-8"))
CHANGES = diff_specs(V1, V2)
REGISTRY, VOCAB = impact.load_registry(S / "regression" / "registry.json")


# ---------------------------------------------------------------- ① 변경점

def test_diff_detects_modified_and_added_only():
    kinds = [(c.kind, c.section) for c in CHANGES]
    assert kinds == [("modified", "예상 도착 시간"), ("modified", "주문 취소"), ("added", "결제 정보"), ("added", "결제 정보")]
    assert "가게 확인 중" in CHANGES[0].before and "도착 시간 계산 중" in CHANGES[0].after


def test_diff_removed_sentence():
    changes = diff_specs(V1, V1.replace("- 현재 단계는 강조 표시하고, 지나간 단계는 완료 표시한다.\n", ""))
    assert [(c.kind, c.before[:8]) for c in changes] == [("removed", "현재 단계는 강")]


def test_split_ignores_titles_and_quotes():
    assert all(not s.text.startswith("#") for s in split_sentences(V2))


# ---------------------------------------------------------------- ③ 정책

def test_policy_search_finds_topic_clause_without_word_overlap():
    hits = [c.id for c in policy.search(CLAUSES, "결제 정보 쿠폰을 여러 장 적용한 주문은 적용된 쿠폰명을 모두 표시한다.")]
    assert "CPN-01" in hits    # '여러 장' ↔ '1장만' : 글자는 안 겹치지만 같은 주제


def test_quote_must_match_clause_text():
    assert policy.quote_matches(CLAUSES, "CPN-01", "쿠폰은 주문 1건당 1장만 사용할 수 있다")
    assert not policy.quote_matches(CLAUSES, "CPN-01", "쿠폰은 주문 1건당 최대 3장까지 사용할 수 있다")
    assert not policy.quote_matches(CLAUSES, "CPN-99", "쿠폰은 주문 1건당 1장만 사용할 수 있다")
    assert not policy.quote_matches(CLAUSES, "CPN-02", "쿠폰은 주문 1건당 1장만 사용할 수 있다")  # 다른 조항 문장


# ---------------------------------------------------------------- ④ 검증기

def _ref(cid, rel="근거"):
    return {"clause_id": cid, "quote": CLAUSES[cid].text.rstrip("."), "relation": rel}


def _req(rid, change, evidence, refs=(), tags=("coupon",)):
    return {"id": rid, "change_ids": [change], "summary": rid, "spec_evidence": evidence, "feature_tags": list(tags),
            "policy_refs": list(refs), "derived_conditions": [], "risk": "low", "destructive": False}


def _tc(tid, reqs, spec="", refs=(), expected=("쿠폰명이 표시된다",)):
    return {"id": tid, "requirement_ids": list(reqs), "type": "기능", "title": tid, "precondition": "", "steps": ["진입"],
            "expected": list(expected), "spec_evidence": spec, "policy_refs": list(refs), "feature_tags": ["coupon"],
            "automatable": True}


C3 = CHANGES[2].after


def test_verifier_rejects_fabricated_spec_and_policy():
    reqs = {"requirements": [_req("R1", "C3", C3, [_ref("CPN-03", "제약"), _ref("PAY-02", "제약"),
                                                   {"clause_id": "CPN-04", "quote": "지어낸 조항", "relation": "제약"}]),
                             _req("R2", "C3", "기획서에 없는 문장이다."),
                             _req("R3", "C9", C3)],
            "policy_issues": [{"requirement_id": "R1", "clause_id": "CPN-01", "quote": "1장 이상 사용 가능", "issue": "x"}],
            "open_questions": []}
    tcs = {"testcases": [_tc("TC-1", ["R1"], spec=C3),
                         _tc("TC-2", ["R1"], refs=[_ref("CPN-03")]),
                         _tc("TC-3", ["R1"], refs=[{"clause_id": "CPN-03", "quote": "할인은 빨간색으로 표시", "relation": "제약"}]),
                         _tc("TC-4", ["R1"]),                       # 근거 없음
                         _tc("TC-5", ["R2"], spec=C3)]}             # 무효 요구사항 참조
    v = verifier.verify(CHANGES, V1, V2, CLAUSES, reqs, tcs, VOCAB)
    assert [r["id"] for r in v.requirements] == ["R1"]
    assert {r["id"] for r in v.invalid_requirements} == {"R2", "R3"}
    assert [p["clause_id"] for p in v.requirements[0]["policy_refs"]] == ["CPN-03", "PAY-02"]
    assert any("CPN-04" in d for d in v.dropped_policy_claims)
    assert v.policy_issues == []                                    # 인용이 틀린 충돌 주장은 버림
    assert [t["id"] for t in v.accepted] == ["TC-1", "TC-2"]
    assert [g["clause_id"] for g in v.policy_gaps] == ["PAY-02"]    # 근거 있는 정책 조항인데 TC 없음
    assert set(v.unanalyzed_changes) == {"C1", "C2", "C4"}


def test_verifier_flags_vague_expected():
    reqs = {"requirements": [_req("R1", "C3", C3)], "policy_issues": [], "open_questions": []}
    v = verifier.verify(CHANGES, V1, V2, CLAUSES, reqs, {"testcases": [_tc("TC-1", ["R1"], spec=C3,
                                                                           expected=["정상적으로 표시된다"])]}, VOCAB)
    assert any("모호" in w for w in v.warnings)


# ---------------------------------------------------------------- ⑤ 영향 분석

class Stub:
    def __init__(self, *answers):
        self.answers = list(answers)

    def complete_json(self, key, system, user, schema):
        return self.answers.pop(0)


def _verification(tags, refs):
    v = verifier.Verification()
    v.requirements = [{"id": "R1", "summary": "", "feature_tags": tags, "policy_refs": refs}]
    return v


def test_impact_selects_by_tag_and_policy_and_flags_expected_update():
    result = impact.select(REGISTRY, _verification(["eta"], [_ref("PAY-01")]), CHANGES)
    picked = {s.script.id: s for s in result.selected}
    assert set(picked) == {"S03", "S05"}
    assert picked["S03"].expect_update and not picked["S05"].expect_update


def test_impact_llm_suggestion_must_exist():
    llm = Stub({"suggestions": [{"script_id": "S02", "reason": "레이아웃"}, {"script_id": "S77", "reason": "?"}]})
    result = impact.select(REGISTRY, _verification(["eta"], []), CHANGES, llm)
    assert "S02" in {s.script.id for s in result.selected}
    assert any("S77" in r for r in result.rejected_suggestions)


def test_failure_classification():
    assert runner.classify("failed", expect_update=True).startswith("스크립트 갱신")
    assert runner.classify("failed", expect_update=False) == "결함 의심"
    assert runner.classify("passed", expect_update=True) == "통과"


# ---------------------------------------------------------------- 에이전트 가드레일·가상 드라이버

def _d(action, **extra):
    return {"observation": "", "action": action, "reason": "", **extra}


ENTER = [_d({"type": "tap", "element": "e8"}), _d({"type": "tap", "element": "e3"})]
TC = {"id": "TC-X", "title": "x", "expected": ["할인 표시", "취소 버튼"]}


def _agent(decisions, **cfg):
    return TestAgent(Stub(*decisions), FakeDevice(S / "app" / "flow.json"), AgentConfig(**cfg))


def test_pass_claim_without_all_expected_is_inconclusive():
    verify = _d({"type": "verify"}, checks=[{"expected_index": 0, "element": "e12", "property": "text",
                                             "match": "contains", "expected": "1,000원"}])
    finish = _d({"type": "finish"}, finish={"result": "pass", "reason": ""})
    assert _agent(ENTER + [verify, finish]).run(TC).status == "inconclusive"


def test_code_comparison_overrides_llm():
    verify = _d({"type": "verify"}, checks=[
        {"expected_index": 0, "element": "e12", "property": "text", "match": "regex", "expected": "할인 -[0-9,]+원"},
        {"expected_index": 1, "element": "e14", "property": "text", "match": "exact", "expected": "주문 취소"}])
    finish = _d({"type": "finish"}, finish={"result": "pass", "reason": ""})
    assert _agent(ENTER + [verify, finish]).run(TC).status == "fail"


def test_destructive_blocked_and_hallucination_rejected():
    assert _agent(ENTER + [_d({"type": "tap", "element": "e14"})]).run(TC).status == "blocked"
    bad = _d({"type": "tap", "element": "e99"})
    assert _agent(ENTER + [bad, bad, bad], max_invalid=2).run(TC).status == "inconclusive"


def test_fake_driver_supports_appium_locators():
    drv = FakeDriver(FakeDevice(S / "app" / "flow.json"))
    drv.find_element("accessibility id", "하단탭바 주문내역탭").click()
    drv.find_element("id", "com.sampleapp:id/order_item_ongoing").click()
    assert drv.find_element("-android uiautomator", 'new UiSelector().text("주문 취소")').is_enabled()
    with pytest.raises(Exception):
        drv.find_element("id", "com.sampleapp:id/nothing")


# ---------------------------------------------------------------- 코드 게이트

def test_code_gate_blocks_ai_generated_java():
    report = code_checker.check_code(S / "ai_generated" / "OrderStatusAiTest.java", code_checker.load_screens(S / "app"),
                                     "com.sampleapp")
    rules = " | ".join(f.rule for f in report.findings)
    assert report.verdict == "차단"
    for expected in ("고정 대기", "부분 일치", "인덱스 XPath", "비밀값", "의미 없는 검증", "order_status_badge"):
        assert expected in rules, expected
