import csv
import io
import re
from collections import defaultdict
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP

from fastapi import HTTPException

from app.schemas import AssistantResponse


def analyze_sales(message: str) -> AssistantResponse:
    reader = csv.DictReader(io.StringIO(message.lstrip('\ufeff')), strict=True)
    try:
        headers = reader.fieldnames or []
        names = {name.strip().lower(): name for name in headers}
        amount_key = next((names[key] for key in ['매출', '매출액', 'amount', 'sales'] if key in names), None)
        date_key = next((names[key] for key in ['날짜', 'date', '일시', 'datetime'] if key in names), None)
        hour_key = next((names[key] for key in ['시간', 'hour'] if key in names), None)
        if not amount_key:
            raise ValueError('매출 또는 amount 열이 필요합니다.')
        total, count = Decimal(0), 0
        daily, hourly = defaultdict(Decimal), defaultdict(Decimal)
        for row in reader:
            if not any(value for value in row.values()):
                continue
            count += 1
            if count > 50_000:
                raise ValueError('한 번에 최대 50,000개 매출 내역을 분석할 수 있습니다.')
            if None in row or any(value is None for value in row.values()):
                raise ValueError(f'{reader.line_num}행의 열 개수가 제목 행과 다릅니다.')
            raw = re.sub(r'[,₩원\s]', '', row[amount_key])
            try:
                amount = Decimal(raw)
                if not amount.is_finite() or abs(amount) > Decimal('100000000000000'):
                    raise InvalidOperation
            except InvalidOperation as exc:
                raise ValueError(f'{reader.line_num}행의 매출은 유효한 숫자여야 합니다.') from exc
            total += amount
            if date_key and row[date_key].strip():
                date = re.split(r'[T\s]', row[date_key].strip())[0]
                daily[date] += amount
            if hour_key and row[hour_key].strip():
                value = row[hour_key].strip()
                if not re.fullmatch(r'(?:[01]?\d|2[0-3])(?::[0-5]\d)?', value):
                    raise ValueError(f'{reader.line_num}행의 시간은 0~23 또는 HH:mm 형식이어야 합니다.')
                hourly[int(value.split(':')[0])] += amount
        if not count:
            raise ValueError('분석할 매출 내역이 없습니다.')
    except (csv.Error, ValueError) as exc:
        raise HTTPException(422, str(exc)) from exc

    def money(value: Decimal) -> str:
        return f'{value.quantize(Decimal("1"), rounding=ROUND_HALF_UP):,}원'

    lines = ['매출 데이터 분석', '', f'총매출: {money(total)}', f'매출 내역: {count:,}건', f'내역 1건당 평균 매출: {money(total / count)}']
    if daily:
        day = max(daily, key=daily.get)
        lines += [f'매출이 가장 높은 날: {day} ({money(daily[day])})', f'일평균 매출: {money(total / len(daily))}']
    if hourly:
        hour = max(hourly, key=hourly.get)
        lines += [f'매출이 가장 높은 시간: {hour}시 ({money(hourly[hour])})', '', f'{hour}시 전후의 주문량과 근무 인원을 함께 점검해 보세요.']
    lines += ['', '식재료 원가·인건비는 포함하지 않았습니다. 일평균은 데이터가 있는 날짜 수를 기준으로 계산합니다.', '이 결과는 실제 입력값을 계산한 것으로, AI 예측이나 수익 분석이 아닙니다.']
    return AssistantResponse(text='\n'.join(lines), mode='calculated')
