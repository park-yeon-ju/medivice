"""
작성자 : 박기준
작성목적 : 인증키 발급 전에도 파이프라인 전체를 돌려볼 수 있도록,
          실제 식약처 API 응답과 '같은 모양'의 표본 원본 JSON을 만든다.
          필드명·중첩 구조·(A,B)/(B,A) 양방향 중복까지 실제 응답 특성을 그대로 재현한다.
작성일 : 2026-09-02
실행    : python src/make_sample_raw.py
출력    : data/raw/<dataset>/page_0001.json
주의    : 여기 값은 파이프라인 검증용 표본이다. 실제 판정에 쓰지 말 것.
"""
import json
from pathlib import Path

import config


def wrap(items):
    """DUR API 응답 봉투 재현"""
    return {"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
            "body": {"pageNo": 1, "numOfRows": 100, "totalCount": len(items), "items": items}}


def write(name, items):
    d = config.RAW_DIR / name
    d.mkdir(parents=True, exist_ok=True)
    (d / "page_0001.json").write_text(json.dumps(wrap(items), ensure_ascii=False, indent=2),
                                      encoding="utf-8")
    print(f"  {name:<20} {len(items)}건")


# 등장 성분
ACE = ("D000581", "아세트아미노펜", "Acetaminophen")
AMB = ("D000133", "암브록솔염산염", "Ambroxol HCl")
MAG = ("D001204", "산화마그네슘", "Magnesium Oxide")
KET = ("D000902", "케토롤락트로메타민", "Ketorolac Tromethamine")
IBU = ("D000771", "이부프로펜", "Ibuprofen")
WAR = ("D001988", "와파린나트륨", "Warfarin Sodium")


def item(seq, name, entp, ingr, chart="흰색 원형 정제"):
    """DUR 단일 데이터셋(임부·연령·용량·노인)의 실제 필드 구성.
       성분 국문명 키가 INGR_NAME 이다 — 병용금기의 INGR_KOR_NAME 과 다르다."""
    return {"ITEM_SEQ": seq, "ITEM_NAME": name, "ENTP_NAME": entp, "CHART": chart,
            "MIX_TYPE": "단일", "ETC_OTC_NAME": "일반의약품",
            "INGR_CODE": ingr[0], "INGR_NAME": ingr[1], "INGR_ENG_NAME": ingr[2]}


def pair(seq, name, entp, ingr, mseq, mname, mentp, mingr, content, date="20180131"):
    """병용금기만 성분명 키가 INGR_KOR_NAME 이고 MIXTURE_* 짝을 갖는다."""
    row = item(seq, name, entp, ingr)
    row.pop("INGR_NAME")
    row["INGR_KOR_NAME"] = ingr[1]
    row.update({"TYPE_NAME": "병용금기", "DUR_SEQ": seq[-3:],
                "MIXTURE_ITEM_SEQ": mseq, "MIXTURE_ITEM_NAME": mname, "MIXTURE_ENTP_NAME": mentp,
                "MIXTURE_INGR_CODE": mingr[0], "MIXTURE_INGR_KOR_NAME": mingr[1],
                "MIXTURE_INGR_ENG_NAME": mingr[2],
                "PROHBT_CONTENT": content, "NOTIFICATION_DATE": date, "DEL_YN": "정상"})
    return row


# ── ① 병용금기 : (A,B)와 (B,A)를 모두 넣어 실제 API의 양방향 중복을 재현한다 ──
CONTENT = "NSAIDs 병용 시 위장관 출혈 및 신독성 위험이 증가하므로 병용을 금기한다."
usjnt = [
    pair("201800101", "케토신정 10mg", "가나제약", KET,
         "201800202", "부루펜정 200mg", "다라제약", IBU, CONTENT),
    # ↓ 같은 쌍이 방향만 바꿔 한 번 더 내려온다 (정규화 단계에서 1건으로 접혀야 정상)
    pair("201800202", "부루펜정 200mg", "다라제약", IBU,
         "201800101", "케토신정 10mg", "가나제약", KET, CONTENT),
    pair("201800303", "와파정 5mg", "마바제약", WAR,
         "201800202", "부루펜정 200mg", "다라제약", IBU,
         "항응고 작용이 증강되어 출혈 위험이 높아지므로 병용을 금기한다."),
]

# ── ② 용량주의 → ingredient_daily_limits 의 원천 ──
cpcty = [
    {**item("201900404", "○○정 500mg", "사아제약", ACE),
     "TYPE_NAME": "용량주의", "MAX_QTY": "4000mg", "MAX_QTY_UNIT": "mg",
     "PROHBT_CONTENT": "성인 1일 최대 4000mg 을 초과하여 투여하지 않는다.",
     "NOTIFICATION_DATE": "20200401"},
    {**item("201900505", "이부펜정 200mg", "다라제약", IBU),
     "TYPE_NAME": "용량주의", "MAX_QTY": "3200mg", "MAX_QTY_UNIT": "mg",
     "PROHBT_CONTENT": "성인 1일 최대 3200mg 을 초과하여 투여하지 않는다.",
     "NOTIFICATION_DATE": "20200401"},
]

# ── ③ 임부금기 ──
pwnm = [
    {**item("201800303", "와파정 5mg", "마바제약", WAR),
     "TYPE_NAME": "임부금기", "PROHBT_CONTENT": "태아 기형 유발 위험이 있어 임부에게 투여를 금기한다.",
     "NOTIFICATION_DATE": "20170701"},
]

# ── ④ 특정연령대금기 ──
age = [
    {**item("201800101", "케토신정 10mg", "가나제약", KET),
     "TYPE_NAME": "연령금기", "PROHBT_CONTENT": "만 18세 미만 소아에게는 투여하지 않는다.",
     "NOTIFICATION_DATE": "20190301"},
]

# ── ⑤ 노인주의 ──
odsn = [
    {**item("201800202", "부루펜정 200mg", "다라제약", IBU),
     "TYPE_NAME": "노인주의", "PROHBT_CONTENT": "65세 이상에서는 위장관 출혈 위험이 증가하므로 주의한다.",
     "NOTIFICATION_DATE": "20190301"},
]

# ── ⑥ 효능군중복 : 성분 쌍이 아니라 '성분 → 효능군' 분류다 (MIXTURE_* 필드 없음) ──
efcy = [
    {**item("201900404", "○○정 500mg", "사아제약", ACE),
     "TYPE_NAME": "효능군중복", "DUR_SEQ": "2463",
     "EFFECT_NAME": "해열진통소염제", "SERS_NAME": "아닐리드계",
     "NOTIFICATION_DATE": "20200401"},
    {**item("201900606", "○○ 종합감기약 325mg", "아자제약", ACE),
     "TYPE_NAME": "효능군중복", "DUR_SEQ": "2463",
     "EFFECT_NAME": "해열진통소염제", "SERS_NAME": "아닐리드계",
     "NOTIFICATION_DATE": "20200401"},
    {**item("201800202", "부루펜정 200mg", "다라제약", IBU),
     "TYPE_NAME": "효능군중복", "DUR_SEQ": "2464",
     "EFFECT_NAME": "해열진통소염제", "SERS_NAME": "비스테로이드성 소염제",
     "NOTIFICATION_DATE": "20200401"},
]

# ── ⑦ e약은요 (필드명이 camelCase 인 점까지 재현) ──
easy = [
    {"itemSeq": "201900404", "itemName": "○○정 500mg", "entpName": "사아제약",
     "efcyQesitm": "감기로 인한 발열 및 통증, 두통, 신경통에 사용합니다.",
     "useMethodQesitm": "성인 1회 1정, 1일 3회 복용합니다.",
     "atpnWarnQesitm": "1일 4000mg 을 초과하지 마십시오.",
     "atpnQesitm": "간장애 환자는 의사와 상의하십시오.",
     "intrcQesitm": "알코올과 함께 복용하지 마십시오.",
     "seQesitm": "드물게 발진, 구역이 나타날 수 있습니다.",
     "depositMethodQesitm": "실온에서 보관하십시오.",
     "itemImage": "https://nedrug.mfds.go.kr/sample/201900404.jpg"},
    {"itemSeq": "201900606", "itemName": "○○ 종합감기약 325mg", "entpName": "아자제약",
     "efcyQesitm": "감기의 제증상(콧물, 코막힘, 재채기, 인후통, 기침, 가래, 오한, 발열) 완화에 사용합니다.",
     "useMethodQesitm": "성인 1회 1포, 1일 3회 복용합니다.",
     "atpnWarnQesitm": "아세트아미노펜 함유 제제와 중복 복용하지 마십시오.",
     "atpnQesitm": "졸음이 올 수 있으므로 운전을 피하십시오.",
     "intrcQesitm": "다른 해열진통제와 병용하지 마십시오.",
     "seQesitm": "졸음, 구갈이 나타날 수 있습니다.",
     "depositMethodQesitm": "실온에서 보관하십시오.",
     "itemImage": "https://nedrug.mfds.go.kr/sample/201900606.jpg"},
]


# ── ⑧ 제품 허가정보 : MATERIAL_NAME 원문 형식을 그대로 재현 ──
#     복합제(종합감기약)는 성분 묶음이 ';' 로 이어 붙어 온다 → 1NF 분해 대상
def mat(*comps):
    """(성분명, 분량, 단위, 총량표기) 튜플들을 MATERIAL_NAME 원문으로 조립"""
    return ";".join(
        f"총량 : {tot}|성분명 : {n}|분량 : {q}|단위 : {u}|규격 : KP|성분정보 : |비고 : "
        for n, q, u, tot in comps) + ";"


permit_rows = [
    {"ITEM_SEQ": "201900404", "ITEM_NAME": "○○정 500mg", "ENTP_NAME": "사아제약",
     "ETC_OTC_CODE": "일반의약품", "CHART": "흰색 원형 정제",
     "MATERIAL_NAME": mat(("아세트아미노펜", "500", "밀리그램", "1정 중-"))},

    # 복합제 — 아세트아미노펜은 DUR 코드로 매칭되고, 클로르페니라민은 매칭되지 않는다.
    # 후자는 LOCAL_ 코드로 등록되어 '판정 근거 없는 성분'으로 드러난다.
    {"ITEM_SEQ": "201900606", "ITEM_NAME": "○○ 종합감기약 325mg", "ENTP_NAME": "아자제약",
     "ETC_OTC_CODE": "일반의약품", "CHART": "미황색 과립",
     "MATERIAL_NAME": mat(("아세트아미노펜", "325", "밀리그램", "1포 중-"),
                          ("클로르페니라민말레산염", "2.5", "밀리그램", "1포 중-"))},

    {"ITEM_SEQ": "201800202", "ITEM_NAME": "부루펜정 200mg", "ENTP_NAME": "다라제약",
     "ETC_OTC_CODE": "일반의약품", "CHART": "분홍색 원형 정제",
     "MATERIAL_NAME": mat(("이부프로펜", "200", "밀리그램", "1정 중-"))},

    {"ITEM_SEQ": "201800101", "ITEM_NAME": "케토신정 10mg", "ENTP_NAME": "가나제약",
     "ETC_OTC_CODE": "전문의약품", "CHART": "흰색 타원형 정제",
     "MATERIAL_NAME": mat(("케토롤락트로메타민", "10", "밀리그램", "1정 중-"))},

    {"ITEM_SEQ": "201800303", "ITEM_NAME": "와파정 5mg", "ENTP_NAME": "마바제약",
     "ETC_OTC_CODE": "전문의약품", "CHART": "연분홍색 원형 정제",
     "MATERIAL_NAME": mat(("와파린나트륨", "5", "밀리그램", "1정 중-"))},
]


def main():
    print("[표본 생성] 실제 API 응답과 동일한 구조")
    config.RAW_DIR.mkdir(parents=True, exist_ok=True)
    # 이 데이터가 표본임을 남긴다. 이후 단계가 이 표시를 따라 경고를 띄운다.
    (config.RAW_DIR / "_SOURCE.txt").write_text("SAMPLE\n", encoding="utf-8")
    write("dur_usjnt_taboo", usjnt)
    write("dur_efcy_dplct", efcy)
    write("dur_pwnm_taboo", pwnm)
    write("dur_age_taboo", age)
    write("dur_cpcty_atent", cpcty)
    write("dur_odsn_atent", odsn)
    write("drug_easy_info", easy)
    write("drug_permit_info", permit_rows)


if __name__ == "__main__":
    main()
