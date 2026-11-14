use anyhow::{Context, Result, bail};
use chrono::{Datelike, Days, Local, Months, NaiveDate, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use std::{fmt, str::FromStr};

/// Canonical, non-nil UUID. New identities use v4 and never depend on the clock.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct Id([u8; 16]);
impl Id {
    pub fn new() -> Result<Self> {
        let mut bytes = [0; 16];
        getrandom::fill(&mut bytes).map_err(|e| anyhow::anyhow!("无法生成 UUID: {e}"))?;
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        Ok(Self(bytes))
    }
}
impl fmt::Display for Id {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let h = hex::encode(self.0);
        write!(
            f,
            "{}-{}-{}-{}-{}",
            &h[..8],
            &h[8..12],
            &h[12..16],
            &h[16..20],
            &h[20..]
        )
    }
}
impl From<Id> for String {
    fn from(id: Id) -> Self {
        id.to_string()
    }
}
impl TryFrom<String> for Id {
    type Error = anyhow::Error;
    fn try_from(value: String) -> Result<Self> {
        value.parse()
    }
}
impl FromStr for Id {
    type Err = anyhow::Error;
    fn from_str(value: &str) -> Result<Self> {
        if value.len() != 36 || [8, 13, 18, 23].iter().any(|&i| value.as_bytes()[i] != b'-') {
            bail!("UUID 格式不合法");
        }
        let raw = value.replace('-', "");
        if raw.len() != 32
            || !raw
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            bail!("UUID 必须为小写标准格式");
        }
        let mut bytes = [0; 16];
        hex::decode_to_slice(raw, &mut bytes)?;
        if bytes == [0; 16] {
            bail!("UUID 不能为 nil");
        }
        Ok(Self(bytes))
    }
}
impl rusqlite::ToSql for Id {
    fn to_sql(&self) -> rusqlite::Result<rusqlite::types::ToSqlOutput<'_>> {
        Ok(rusqlite::types::ToSqlOutput::Owned(self.to_string().into()))
    }
}
impl rusqlite::types::FromSql for Id {
    fn column_result(value: rusqlite::types::ValueRef<'_>) -> rusqlite::types::FromSqlResult<Self> {
        value
            .as_str()?
            .parse()
            .map_err(|e: anyhow::Error| rusqlite::types::FromSqlError::Other(e.into()))
    }
}
pub fn now() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Nanos, true)
}
pub fn parse_date(value: &str) -> Result<NaiveDate> {
    let date = NaiveDate::parse_from_str(value, "%Y-%m-%d")?;
    if !(1..=9999).contains(&date.year()) || date.to_string() != value {
        bail!("自然日必须为 YYYY-MM-DD，年份 0001–9999");
    }
    Ok(date)
}
pub fn deserialize_date<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> std::result::Result<NaiveDate, D::Error> {
    let value = String::deserialize(deserializer)?;
    parse_date(&value).map_err(serde::de::Error::custom)
}
pub fn validate_time(value: &str) -> Result<()> {
    let parsed = chrono::DateTime::parse_from_rfc3339(value).context("时刻不合法")?;
    if !(1..=9999).contains(&parsed.year())
        || parsed
            .with_timezone(&Utc)
            .to_rfc3339_opts(SecondsFormat::Nanos, true)
            != value
    {
        bail!("时刻必须为 UTC 固定九位小数");
    }
    Ok(())
}
pub const CATEGORIES: [(&str, &str); 5] = [
    ("cash", "流动资金"),
    ("fixed", "固定资产"),
    ("investment", "投资理财"),
    ("receivable", "应收款"),
    ("debt", "负债"),
];
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Asset {
    pub id: Id,
    pub revision_uid: Id,
    pub current_history_id: Id,
    pub category_code: String,
    pub name: String,
    pub amount_cents: i64,
    pub created_at: String,
    pub updated_at: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct History {
    pub id: Id,
    pub asset_id: Id,
    pub previous_id: Option<Id>,
    pub original_amount_cents: i64,
    pub amount_cents: i64,
    pub recorded_at: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Correction {
    pub id: Id,
    pub history_id: Id,
    pub previous_amount_cents: i64,
    pub amount_cents: i64,
    pub recorded_at: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Subscription {
    pub id: Id,
    pub revision_uid: Id,
    pub name: String,
    pub amount_cents: i64,
    pub currency: String,
    pub cycle: String,
    pub next_charge_on: NaiveDate,
    pub status: String,
    pub created_at: String,
    pub updated_at: String,
}
#[derive(Clone, Debug, Default, Serialize)]
pub struct Ledger {
    pub assets: Vec<Asset>,
    pub history: Vec<History>,
    pub subscriptions: Vec<Subscription>,
}
impl Ledger {
    pub fn validate(&self) -> Result<()> {
        for a in &self.assets {
            let current = self
                .history
                .iter()
                .find(|h| h.id == a.current_history_id && h.asset_id == a.id)
                .context("当前余额快照不存在")?;
            if a.amount_cents != current.amount_cents {
                bail!("余额与当前快照不一致");
            }
        }
        Ok(())
    }
}
/// User-editable subscription fields, separate from stored identity and timestamps.
#[derive(Clone, Debug)]
pub struct SubscriptionInput {
    pub name: String,
    pub amount_cents: i64,
    pub currency: String,
    pub cycle: String,
    pub next_charge_on: NaiveDate,
    pub status: String,
}
impl From<&Subscription> for SubscriptionInput {
    fn from(s: &Subscription) -> Self {
        Self {
            name: s.name.clone(),
            amount_cents: s.amount_cents,
            currency: s.currency.clone(),
            cycle: s.cycle.clone(),
            next_charge_on: s.next_charge_on,
            status: s.status.clone(),
        }
    }
}
impl SubscriptionInput {
    pub fn validate(&self) -> Result<()> {
        validate_subscription(
            &self.name,
            self.amount_cents,
            &self.currency,
            &self.cycle,
            self.next_charge_on,
            &self.status,
        )
    }
}
fn validate_subscription(
    name: &str,
    cents: i64,
    currency: &str,
    cycle: &str,
    date: NaiveDate,
    status: &str,
) -> Result<()> {
    if name.trim().is_empty()
        || cents < 0
        || !["CNY", "USD"].contains(&currency)
        || !["weekly", "monthly", "quarterly", "yearly"].contains(&cycle)
        || !["active", "paused"].contains(&status)
        || !(1..=9999).contains(&date.year())
    {
        bail!("订阅名称、金额、币种、周期、日期或状态不合法");
    }
    Ok(())
}
impl Subscription {
    pub fn validate(&self) -> Result<()> {
        validate_subscription(
            &self.name,
            self.amount_cents,
            &self.currency,
            &self.cycle,
            self.next_charge_on,
            &self.status,
        )
    }
    pub fn annual(&self) -> Result<i64> {
        self.amount_cents
            .checked_mul(match self.cycle.as_str() {
                "weekly" => 52,
                "monthly" => 12,
                "quarterly" => 4,
                "yearly" => 1,
                _ => bail!("非法扣费周期"),
            })
            .context("订阅年化金额超出范围")
    }
    pub fn renew(&self, today: NaiveDate) -> Result<(NaiveDate, u32)> {
        let mut date = self.next_charge_on;
        let mut periods = 0u32;
        while date <= today {
            date = match self.cycle.as_str() {
                "weekly" => date.checked_add_days(Days::new(7)),
                "monthly" => date.checked_add_months(Months::new(1)),
                "quarterly" => date.checked_add_months(Months::new(3)),
                "yearly" => date.checked_add_months(Months::new(12)),
                _ => bail!("非法扣费周期"),
            }
            .context("日期超出范围")?;
            periods = periods.checked_add(1).context("续费期数超出范围")?;
        }
        if date.year() > 9999 {
            bail!("日期超出支持范围");
        }
        Ok((date, periods))
    }
    pub fn group(&self, today: NaiveDate) -> usize {
        if self.status == "paused" {
            4
        } else if self.next_charge_on < today {
            0
        } else if self.next_charge_on.year() == today.year()
            && self.next_charge_on.month() == today.month()
        {
            1
        } else {
            let next = today
                .with_day(1)
                .unwrap()
                .checked_add_months(Months::new(1));
            if next.is_some_and(|n| {
                n.year() == self.next_charge_on.year() && n.month() == self.next_charge_on.month()
            }) {
                2
            } else {
                3
            }
        }
    }
}
pub fn today() -> NaiveDate {
    Local::now().date_naive()
}
pub fn parse_money(value: &str) -> Result<i64> {
    let value = value.trim();
    if value.is_empty() {
        bail!("请输入金额");
    }
    let parts: Vec<_> = value.split('.').collect();
    if parts.len() > 2
        || parts[0].is_empty()
        || !parts[0].bytes().all(|c| c.is_ascii_digit())
        || parts
            .get(1)
            .is_some_and(|p| p.is_empty() || p.len() > 2 || !p.bytes().all(|c| c.is_ascii_digit()))
    {
        bail!("金额必须为非负数，最多两位小数");
    }
    let whole = parts[0].parse::<i64>().context("金额超出范围")?;
    let fraction = match parts.get(1) {
        None => 0,
        Some(v) => v.parse::<i64>()? * if v.len() == 1 { 10 } else { 1 },
    };
    whole
        .checked_mul(100)
        .and_then(|v| v.checked_add(fraction))
        .context("金额超出范围")
}
pub fn decimal(cents: i64) -> String {
    format!("{}.{:02}", cents / 100, cents % 100)
}
pub fn money(cents: i64, currency: &str) -> String {
    let a = cents.unsigned_abs();
    let whole = (a / 100).to_string();
    let mut grouped = String::new();
    for (i, ch) in whole.chars().enumerate() {
        if i > 0 && (whole.len() - i).is_multiple_of(3) {
            grouped.push(',');
        }
        grouped.push(ch);
    }
    format!(
        "{}{}{}.{:02}",
        if cents < 0 { "−" } else { "" },
        if currency == "USD" { "$" } else { "¥" },
        grouped,
        a % 100
    )
}
pub fn totals(subscriptions: &[Subscription], today: NaiveDate) -> Result<[[i64; 3]; 2]> {
    let mut result = [[0i64; 3]; 2];
    for s in subscriptions.iter().filter(|s| s.status == "active") {
        let index = usize::from(s.currency == "USD");
        if s.next_charge_on.year() == today.year() && s.next_charge_on.month() == today.month() {
            result[index][0] = result[index][0]
                .checked_add(s.amount_cents)
                .context("本月待扣超出范围")?;
        }
        result[index][2] = result[index][2]
            .checked_add(s.annual()?)
            .context("年化支出超出范围")?;
    }
    for r in &mut result {
        r[1] = r[2] / 12 + i64::from(r[2] % 12 >= 6);
    }
    Ok(result)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn money_is_exact_and_rejects_invalid_inputs() {
        assert_eq!(parse_money("0.01").unwrap(), 1);
        assert_eq!(parse_money("123.4").unwrap(), 12340);
        assert_eq!(parse_money("0").unwrap(), 0);
        for v in [
            "",
            "-1",
            "1.001",
            "1e2",
            "NaN",
            ".1",
            "1.",
            "92233720368547759",
        ] {
            assert!(parse_money(v).is_err(), "{v}");
        }
    }
    #[test]
    fn calendar_renewal_and_currency_statistics() -> Result<()> {
        let mut s = Subscription {
            id: Id::new()?,
            revision_uid: Id::new()?,
            created_at: now(),
            updated_at: now(),
            name: "test".into(),
            amount_cents: 101,
            currency: "USD".into(),
            cycle: "monthly".into(),
            next_charge_on: NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
            status: "active".into(),
        };
        let (d, n) = s.renew(NaiveDate::from_ymd_opt(2024, 3, 1).unwrap())?;
        assert_eq!(d.to_string(), "2024-03-29");
        assert_eq!(n, 2);
        let t = totals(&[s.clone()], s.next_charge_on)?;
        assert_eq!(t[0], [0, 0, 0]);
        assert_eq!(t[1], [101, 101, 1212]);
        s.status = "paused".into();
        assert_eq!(totals(&[s.clone()], s.next_charge_on)?, [[0; 3]; 2]);
        Ok(())
    }
}
