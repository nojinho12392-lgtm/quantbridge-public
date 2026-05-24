import SwiftUI

struct DetailSummarySection: View {
    let info: StockInfo?
    let updatedAt: String?
    let currency: String
    var isETF = false
    var onTermSelected: (GlossaryTerm) -> Void = { _ in }

    private var metrics: [DetailSummaryMetric] {
        let base = [
            priceMomentumMetric,
            rangePositionMetric,
            updatedMetric
        ]
        return isETF ? base : [priceMomentumMetric, rangePositionMetric, valuationMetric, updatedMetric]
    }

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            ForEach(metrics) { metric in
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 4) {
                        Text(metric.label)
                            .font(.caption)
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(1)
                        GlossaryInfoButton(label: metric.detail, onSelect: onTermSelected)
                        if glossaryTerm(for: metric.detail) == nil {
                            GlossaryInfoButton(label: metric.label, onSelect: onTermSelected)
                        }
                    }
                    Text(metric.value)
                        .font(.system(size: 18, weight: .bold, design: .rounded))
                        .foregroundStyle(metric.color)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                    Text(metric.detail)
                        .font(.system(size: 12))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 8).fill(AppTheme.elevatedCard))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(AppTheme.hairline, lineWidth: 0.5)
                )
            }
        }
        .padding(.horizontal)
        .padding(.bottom, 4)
    }

    private var priceMomentumMetric: DetailSummaryMetric {
        guard let current = info?.currentPrice, let prev = info?.prevClose, prev != 0 else {
            return DetailSummaryMetric(label: "당일 흐름", value: "-", detail: "전일 종가 없음", color: AppTheme.secondaryText)
        }
        let label = info?.dailyChangeHorizon?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            ? info?.dailyChangeHorizon ?? "당일 흐름"
            : "당일 흐름"
        let changePct = info?.dailyChangePct ?? (current / prev) - 1
        return DetailSummaryMetric(
            label: label,
            value: pct(changePct),
            detail: fmtPx(current - prev, currency: currency),
            color: changePct >= 0 ? .red : .blue
        )
    }

    private var rangePositionMetric: DetailSummaryMetric {
        guard let current = info?.currentPrice, let low = info?.week52Low, let high = info?.week52High, high > low else {
            return DetailSummaryMetric(label: "52주 위치", value: "-", detail: "범위 데이터 없음", color: AppTheme.secondaryText)
        }
        let position = min(max((current - low) / (high - low), 0), 1)
        return DetailSummaryMetric(
            label: "52주 위치",
            value: String(format: "%.0f%%", position * 100),
            detail: position >= 0.75 ? "고점권" : position <= 0.25 ? "저점권" : "중간권",
            color: position >= 0.75 ? .orange : AppTheme.primaryText
        )
    }

    private var valuationMetric: DetailSummaryMetric {
        if let pe = info?.forwardPe ?? info?.peRatio {
            return DetailSummaryMetric(
                label: "밸류에이션",
                value: String(format: "%.1fx", pe),
                detail: info?.forwardPe == nil ? "Trailing PER" : "Forward PER",
                color: AppTheme.primaryText
            )
        }
        if let pbr = info?.priceToBook {
            return DetailSummaryMetric(label: "밸류에이션", value: String(format: "%.1fx", pbr), detail: "PBR", color: AppTheme.primaryText)
        }
        return DetailSummaryMetric(label: "밸류에이션", value: "-", detail: valuationUnavailableReason(info), color: AppTheme.secondaryText)
    }

    private var updatedMetric: DetailSummaryMetric {
        let value = formattedUpdateTimestamp(updatedAt)
        return DetailSummaryMetric(label: "업데이트", value: value, detail: "상세 데이터 기준", color: AppTheme.primaryText)
    }
}

private struct DetailSummaryMetric: Identifiable {
    var id: String { label }
    let label: String
    let value: String
    let detail: String
    let color: Color
}

struct DetailDecisionBriefSection: View {
    let info: StockInfo?
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]
    let currency: String
    let updatedAt: String?
    var isETF = false

    private var rows: [DecisionPill] {
        stockDecisionPills(info: info, metrics: staticMetrics, currency: currency, isETF: isETF)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("판단 요약")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
                Text(formattedUpdateTimestamp(updatedAt))
                    .font(.system(size: 12))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
            }
            Text(decisionSummaryText(info: info, signals: signals, metrics: staticMetrics, isETF: isETF))
                .font(.subheadline)
                .foregroundStyle(AppTheme.primaryText)
                .fixedSize(horizontal: false, vertical: true)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(rows) { row in
                    DecisionPillView(row: row)
                }
            }
        }
        .appCard(padding: 12)
        .padding(.horizontal)
        .padding(.bottom, 4)
    }
}

struct DetailActionPlanSection: View {
    let info: StockInfo?
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]
    let currency: String
    var isETF = false

    private var rows: [DetailActionPlanRow] {
        detailActionPlanRows(info: info, metrics: staticMetrics, signals: signals, currency: currency, isETF: isETF)
    }

    var body: some View {
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    LucideIconView(icon: .listOrdered, size: 16)
                        .foregroundStyle(AppTheme.accent)
                    Text("다음 행동")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AppTheme.primaryText)
                    Spacer()
                }

                VStack(spacing: 8) {
                    ForEach(rows) { row in
                        HStack(alignment: .top, spacing: 10) {
                            LucideIconView(icon: lucideIcon(forSystemSymbol: row.systemImage), size: 13)
                                .foregroundStyle(row.color)
                                .frame(width: 24, height: 24)
                                .background(row.color.opacity(0.10), in: Circle())
                            VStack(alignment: .leading, spacing: 3) {
                                Text(row.title)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(AppTheme.primaryText)
                                    .lineLimit(1)
                                Text(row.detail)
                                    .font(.caption)
                                    .foregroundStyle(AppTheme.secondaryText)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .padding(11)
                        .background(AppTheme.elevatedCard, in: RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
            .appCard(padding: 12)
            .padding(.horizontal)
            .padding(.bottom, 4)
        }
    }
}

struct DetailComparisonGuardSection: View {
    let name: String
    let comparisonCount: Int
    let isCompared: Bool
    let openCompare: () -> Void

    private var isReady: Bool {
        isCompared && comparisonCount >= 2
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                LucideIconView(icon: .gitCompare, size: 16)
                    .foregroundStyle(AppTheme.accent)
                Text("비교 전 판단 금지")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
                Text(isReady ? "비교 준비됨" : "비교 필요")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(isReady ? AppTheme.quality : AppTheme.warning)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background((isReady ? AppTheme.quality : AppTheme.warning).opacity(0.10), in: Capsule())
            }

            Text(detail)
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)

            Button(action: openCompare) {
                HStack(spacing: 8) {
                    LucideIconView(icon: isCompared ? .listOrdered : .plus, size: 15)
                    Text(isCompared ? "비교 후보 관리" : "비교 후보에 담기")
                        .font(.caption.weight(.bold))
                    Spacer()
                    Text("\(min(max(comparisonCount, 0), 4))/4")
                        .font(.caption.weight(.bold))
                        .monospacedDigit()
                }
                .foregroundStyle(AppTheme.accent)
                .padding(.horizontal, 12)
                .padding(.vertical, 11)
                .background(AppTheme.accent.opacity(0.09), in: RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(AppTheme.accent.opacity(0.20), lineWidth: 0.6)
                )
            }
            .buttonStyle(.plain)
        }
        .appCard(padding: 12)
        .padding(.horizontal)
        .padding(.bottom, 4)
    }

    private var detail: String {
        if isReady {
            return "\(min(comparisonCount, 4))개 후보가 비교 목록에 있습니다. 점수, 성장성, 가격 위치를 나란히 본 뒤 우선순위를 정하세요."
        }
        if isCompared {
            return "\(name)은 비교 목록에 담겼습니다. 최소 한 개 후보를 더 담아 상대 매력을 확인하세요."
        }
        return "\(name) 하나만 보고 결론내리지 말고, 비슷한 후보와 같이 담아 상대 매력을 먼저 확인하세요."
    }
}

struct DetailMistakeCoachSection: View {
    let profile: InvestmentProfile
    let name: String
    let info: StockInfo?
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]
    let watchItem: WatchlistItem?
    let isCompared: Bool

    private var rows: [DetailGuardrailRow] {
        detailMistakeCoachRows(profile: profile, name: name, info: info, metrics: staticMetrics, signals: signals, watchItem: watchItem, isCompared: isCompared)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                LucideIconView(icon: .shieldCheck, size: 16)
                    .foregroundStyle(AppTheme.accent)
                Text("실수 방지 코치")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
            }
            VStack(spacing: 8) {
                ForEach(rows) { row in
                    DetailGuardrailRowView(row: row)
                }
            }
        }
        .appCard(padding: 12)
        .padding(.horizontal)
        .padding(.bottom, 4)
    }
}

struct InvestmentProfileFitSection: View {
    let profile: InvestmentProfile
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]

    private var rows: [InvestmentProfileFitRow] {
        investmentProfileFitRows(profile: profile, metrics: staticMetrics, signals: signals)
    }

    var body: some View {
        if profile.isConfigured && !rows.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    LucideIconView(icon: .target, size: 16)
                        .foregroundStyle(AppTheme.accent)
                    Text("내 기준 체크")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AppTheme.primaryText)
                    Spacer()
                    Text(profile.headline)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AppTheme.accent)
                }

                VStack(spacing: 8) {
                    ForEach(rows) { row in
                        HStack(alignment: .top, spacing: 10) {
                            LucideIconView(icon: row.icon, size: 13)
                                .foregroundStyle(row.color)
                                .frame(width: 24, height: 24)
                                .background(row.color.opacity(0.10), in: Circle())
                            VStack(alignment: .leading, spacing: 3) {
                                Text(row.title)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(AppTheme.primaryText)
                                    .lineLimit(1)
                                Text(row.detail)
                                    .font(.caption)
                                    .foregroundStyle(AppTheme.secondaryText)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(11)
                        .background(AppTheme.elevatedCard, in: RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
            .appCard(padding: 12)
            .padding(.horizontal)
            .padding(.bottom, 4)
        }
    }
}

struct PersonalizedStockFitSection: View {
    let profile: InvestmentProfile
    let name: String
    let info: StockInfo?
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]

    private var insight: PersonalizedStockInterpretation {
        personalizedStockInterpretation(
            profile: profile,
            name: name,
            info: info,
            metrics: staticMetrics,
            signals: signals
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                LucideIconView(icon: .target, size: 17)
                    .foregroundStyle(insight.color)
                Text("나와의 적합도")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
                Text(insight.label)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(insight.color)
                    .lineLimit(1)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(insight.color.opacity(0.10), in: Capsule())
            }

            Text(insight.headline)
                .font(.headline.weight(.bold))
                .foregroundStyle(insight.color)
                .lineLimit(1)
            Text(insight.detail)
                .font(.subheadline)
                .foregroundStyle(AppTheme.primaryText)
                .fixedSize(horizontal: false, vertical: true)
            Text(insight.action)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)

            if !insight.reasons.isEmpty {
                HStack(spacing: 6) {
                    ForEach(insight.reasons.prefix(3), id: \.self) { reason in
                        Text(reason)
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(1)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 5)
                            .background(AppTheme.elevatedCard, in: Capsule())
                    }
                }
            }
        }
        .appCard(padding: 14, role: .decision)
        .padding(.horizontal)
        .padding(.bottom, 4)
    }
}

struct DetailJudgementMatrixSection: View {
    let info: StockInfo?
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]
    let currency: String
    var isETF = false

    private var cards: [JudgementCard] {
        judgementCards(info: info, metrics: staticMetrics, signals: signals, currency: currency, isETF: isETF)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                LucideIconView(icon: .layoutDashboard, size: 16)
                    .foregroundStyle(AppTheme.accent)
                Text("판단 카드")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(cards) { card in
                    VStack(alignment: .leading, spacing: 7) {
                        HStack(spacing: 6) {
                            LucideIconView(icon: lucideIcon(forSystemSymbol: card.systemImage), size: 12)
                                .foregroundStyle(card.color)
                            Text(card.title)
                                .font(.caption.weight(.bold))
                                .foregroundStyle(AppTheme.secondaryText)
                                .lineLimit(1)
                        }
                        Text(card.headline)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(2)
                            .minimumScaleFactor(0.82)
                            .frame(minHeight: 36, alignment: .topLeading)
                        Text(card.detail)
                            .font(.system(size: 12))
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(3)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, minHeight: 118, alignment: .topLeading)
                    .padding(11)
                    .background(card.color.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(card.color.opacity(0.16), lineWidth: 0.5)
                    )
                }
            }
        }
        .appCard(padding: 12)
        .padding(.horizontal)
        .padding(.bottom, 4)
    }
}

struct EarningsEventPlanSection: View {
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]

    private var rows: [DetailActionPlanRow] {
        earningsEventPlanRows(metrics: staticMetrics, signals: signals)
    }

    var body: some View {
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    LucideIconView(icon: .calendarClock, size: 16)
                        .foregroundStyle(AppTheme.momentum)
                    Text("어닝 이벤트 플랜")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AppTheme.primaryText)
                    Spacer()
                    if let eventDate = detailMetricValue(["예정일", "발표일"], metrics: staticMetrics) {
                        Text(eventDate)
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(1)
                    }
                }

                VStack(spacing: 8) {
                    ForEach(rows) { row in
                        HStack(alignment: .top, spacing: 10) {
                            LucideIconView(icon: lucideIcon(forSystemSymbol: row.systemImage), size: 13)
                                .foregroundStyle(row.color)
                                .frame(width: 24, height: 24)
                                .background(row.color.opacity(0.10), in: Circle())
                            VStack(alignment: .leading, spacing: 3) {
                                Text(row.title)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(AppTheme.primaryText)
                                Text(row.detail)
                                    .font(.caption)
                                    .foregroundStyle(AppTheme.secondaryText)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(11)
                        .background(AppTheme.elevatedCard, in: RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
            .appCard(padding: 12)
            .padding(.horizontal)
            .padding(.bottom, 4)
        }
    }
}

struct ScoreRationaleSection: View {
    let metrics: [StaticMetric]
    let signals: [InvestmentSignal]
    var onTermSelected: (GlossaryTerm) -> Void = { _ in }

    private var rows: [ScoreRationaleRow] {
        scoreRationaleRows(metrics: metrics, signals: signals)
    }

    var body: some View {
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("점수 산정 근거")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .padding(.horizontal)
                VStack(spacing: 8) {
                    ForEach(rows) { row in
                        HStack(alignment: .top, spacing: 10) {
                            RoundedRectangle(cornerRadius: 3)
                                .fill(row.color)
                                .frame(width: 4)
                            VStack(alignment: .leading, spacing: 3) {
                                HStack(spacing: 4) {
                                    Text(row.title)
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundStyle(AppTheme.primaryText)
                                    GlossaryInfoButton(label: row.termLabel, onSelect: onTermSelected)
                                    Spacer(minLength: 6)
                                    Text(row.value)
                                        .font(.caption.weight(.bold))
                                        .foregroundStyle(row.color)
                                }
                                Text(row.detail)
                                    .font(.caption)
                                    .foregroundStyle(AppTheme.secondaryText)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 8).fill(AppTheme.elevatedCard))
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(AppTheme.hairline, lineWidth: 0.5)
                        )
                    }
                }
                .padding(.horizontal)
            }
            .padding(.vertical, 6)
        }
    }
}

struct StaticMetricsSection: View {
    let metrics: [StaticMetric]
    var onTermSelected: (GlossaryTerm) -> Void = { _ in }

    var body: some View {
        if !metrics.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("팩터 지표")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .padding(.horizontal)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    ForEach(metrics) { metric in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 4) {
                                Text(metric.label)
                                    .font(.caption)
                                    .foregroundStyle(AppTheme.secondaryText)
                                    .lineLimit(1)
                                GlossaryInfoButton(label: metric.label, onSelect: onTermSelected)
                            }
                            Text(metric.value)
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(metric.color)
                                .lineLimit(1)
                                .minimumScaleFactor(0.75)
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 8).fill(.secondary.opacity(0.07)))
                    }
                }
                .padding(.horizontal)
            }
            .padding(.vertical, 10)
        }
    }
}

struct InvestmentRationaleSection: View {
    let signals: [InvestmentSignal]

    var body: some View {
        if !signals.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("투자 근거")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .padding(.horizontal)

                VStack(spacing: 8) {
                    ForEach(signals) { signal in
                        HStack(alignment: .top, spacing: 10) {
                            LucideIconView(icon: lucideIcon(forSystemSymbol: signal.systemImage), size: 18)
                                .foregroundStyle(signal.color)
                                .frame(width: 24, height: 24)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(signal.title)
                                    .font(.subheadline.weight(.semibold))
                                Text(signal.detail)
                                    .font(.caption)
                                    .foregroundStyle(AppTheme.primaryText)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            Spacer()
                        }
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 8).fill(signal.color.opacity(0.08)))
                    }
                }
                .padding(.horizontal)
            }
            .padding(.vertical, 10)
        }
    }
}

struct MissingDataNoticeSection: View {
    let info: StockInfo?
    let staticMetrics: [StaticMetric]
    var isETF = false

    private var reasons: [MissingDataReason] {
        missingStockDataReasons(info: info, metrics: staticMetrics, isETF: isETF)
    }

    var body: some View {
        if !reasons.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    LucideIconView(icon: .triangleAlert, size: 15)
                        .foregroundStyle(AppTheme.warning)
                    Text("데이터 공백")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AppTheme.primaryText)
                    Spacer()
                    Text("\(reasons.count)개 확인")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AppTheme.warning)
                }
                ForEach(reasons.prefix(4)) { reason in
                    HStack(alignment: .top, spacing: 8) {
                        Circle()
                            .fill(AppTheme.warning.opacity(0.85))
                            .frame(width: 6, height: 6)
                            .padding(.top, 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(reason.title)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AppTheme.primaryText)
                            Text(reason.detail)
                                .font(.system(size: 12))
                                .foregroundStyle(AppTheme.secondaryText)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            .appCard(padding: 12)
            .padding(.horizontal)
            .padding(.bottom, 4)
        }
    }
}

private struct DecisionPill: Identifiable {
    let id = UUID()
    let title: String
    let value: String
    let detail: String
    let color: Color
}

private struct DecisionPillView: View {
    let row: DecisionPill

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(row.title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(AppTheme.secondaryText)
            Text(row.value)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(row.color)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
            Text(row.detail)
                .font(.system(size: 12))
                .foregroundStyle(AppTheme.secondaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 8).fill(AppTheme.elevatedCard))
    }
}

private struct ScoreRationaleRow: Identifiable {
    let id = UUID()
    let title: String
    let value: String
    let detail: String
    let termLabel: String
    let color: Color
}

private struct MissingDataReason: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
}

private struct DetailActionPlanRow: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let systemImage: String
    let color: Color
}

private struct DetailGuardrailRow: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let icon: LucideIcon
    let color: Color
}

private struct NoBuyFirstRowModel: Identifiable {
    let id = UUID()
    let label: String
    let title: String
    let detail: String
    let icon: LucideIcon
    let color: Color
}

private struct NoBuyFirstRowView: View {
    let row: NoBuyFirstRowModel

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            LucideIconView(icon: row.icon, size: 14)
                .foregroundStyle(row.color)
                .frame(width: 26, height: 26)
                .background(row.color.opacity(0.12), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(row.label)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(row.color)
                    .lineLimit(1)
                Text(row.title)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(2)
                    .minimumScaleFactor(0.86)
                Text(row.detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(11)
        .background(row.color.opacity(0.07), in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(row.color.opacity(0.14), lineWidth: 0.5)
        )
    }
}

private struct DetailGuardrailRowView: View {
    let row: DetailGuardrailRow

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            LucideIconView(icon: row.icon, size: 13)
                .foregroundStyle(row.color)
                .frame(width: 24, height: 24)
                .background(row.color.opacity(0.10), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(row.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
                Text(row.detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(11)
        .background(row.color.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct JudgementCard: Identifiable {
    let id = UUID()
    let title: String
    let headline: String
    let detail: String
    let systemImage: String
    let color: Color
}

private func judgementCards(info: StockInfo?, metrics: [StaticMetric], signals: [InvestmentSignal], currency: String, isETF: Bool = false) -> [JudgementCard] {
    if isETF {
        return etfJudgementCards(info: info, metrics: metrics, signals: signals, currency: currency)
    }
    return [
        upsideJudgementCard(info: info, metrics: metrics, signals: signals),
        riskJudgementCard(info: info, metrics: metrics, currency: currency),
        conditionJudgementCard(info: info, metrics: metrics, signals: signals),
        eventJudgementCard(metrics: metrics, signals: signals)
    ]
}

private func etfJudgementCards(info: StockInfo?, metrics: [StaticMetric], signals: [InvestmentSignal], currency: String) -> [JudgementCard] {
    return [
        JudgementCard(
            title: "상품 성격",
            headline: detailMetricValue(["테마", "유형"], metrics: metrics) ?? signals.first?.title ?? "ETF 정보 확인",
            detail: signals.first?.detail ?? "추종 지수와 구성 비중을 먼저 확인하세요.",
            systemImage: "square.grid.2x2",
            color: AppTheme.accent
        ),
        etfPriceJudgementCard(info: info, currency: currency),
        JudgementCard(
            title: "비용/분배",
            headline: detailMetricValue(["총보수", "분배"], metrics: metrics) ?? "보수 확인",
            detail: "총보수, AUM, 분배 정책을 같은 테마 ETF와 비교하세요.",
            systemImage: "percent",
            color: AppTheme.quality
        ),
        JudgementCard(
            title: "주의점",
            headline: signals.first(where: { $0.title.contains("주의") })?.title ?? "추종 오차와 집중도",
            detail: signals.first(where: { $0.title.contains("주의") })?.detail ?? "구성 상위 종목 쏠림과 가격 변동성을 함께 확인하세요.",
            systemImage: "exclamationmark.triangle.fill",
            color: AppTheme.warning
        )
    ]
}

private func etfPriceJudgementCard(info: StockInfo?, currency: String) -> JudgementCard {
    if let current = info?.currentPrice, let low = info?.week52Low, let high = info?.week52High, high > low {
        let position = min(max((current - low) / (high - low), 0), 1)
        return JudgementCard(
            title: "가격 위치",
            headline: "52주 범위 \(String(format: "%.0f%%", position * 100))",
            detail: "추종 지수 흐름과 함께 진입 구간을 확인하세요.",
            systemImage: "chart.line.uptrend.xyaxis",
            color: position >= 0.75 ? AppTheme.warning : AppTheme.accent
        )
    }
    return JudgementCard(
        title: "가격 위치",
        headline: "차트 확인",
        detail: "기간별 수익률과 변동성을 먼저 확인하세요.",
        systemImage: "chart.xyaxis.line",
        color: AppTheme.accent
    )
}

private func upsideJudgementCard(info: StockInfo?, metrics: [StaticMetric], signals: [InvestmentSignal]) -> JudgementCard {
    if let firstPositive = signals.first(where: { signal in
        let text = "\(signal.title) \(signal.detail)"
        return !text.contains("주의") && !text.contains("부담") && !text.contains("리스크")
    }) {
        return JudgementCard(
            title: "좋은 점",
            headline: firstPositive.title,
            detail: firstPositive.detail,
            systemImage: firstPositive.systemImage,
            color: AppTheme.quality
        )
    }
    if let scoreMetric = metrics.first(where: { scoreMetricLabels.contains(normalizedMetricLabel($0.label)) && $0.value != "-" }) {
        return JudgementCard(
            title: "좋은 점",
            headline: "\(scoreMetric.label) \(scoreMetric.value)",
            detail: "후보군 안에서 상대 매력을 보여주는 핵심 점수입니다.",
            systemImage: "heart.circle.fill",
            color: AppTheme.accent
        )
    }
    if let growth = info?.revenueGrowth, growth.isFinite, growth > 0 {
        return JudgementCard(
            title: "좋은 점",
            headline: "매출 성장 \(pct(growth))",
            detail: "성장 흐름이 유지되는지 마진과 함께 확인하세요.",
            systemImage: "chart.line.uptrend.xyaxis",
            color: AppTheme.quality
        )
    }
    return JudgementCard(
        title: "좋은 점",
        headline: "확인 필요",
        detail: "강한 긍정 신호가 아직 부족합니다. 점수와 재무 데이터를 먼저 확인하세요.",
        systemImage: "doc.text.magnifyingglass",
        color: AppTheme.secondaryText
    )
}

private func riskJudgementCard(info: StockInfo?, metrics: [StaticMetric], currency: String) -> JudgementCard {
    if let warning = metrics.first(where: { metric in
        let label = normalizedMetricLabel(metric.label)
        return label.contains("debt") || label.contains("mdd") || label.contains("risk") || label.contains("부채") || label.contains("리스크")
    }), warning.value != "-" {
        return JudgementCard(
            title: "위험한 점",
            headline: "\(warning.label) \(warning.value)",
            detail: "비중을 키우기 전에 손실 구간과 재무 부담을 함께 보세요.",
            systemImage: "exclamationmark.triangle.fill",
            color: AppTheme.warning
        )
    }
    if let pe = info?.forwardPe ?? info?.peRatio, pe >= 35 {
        return JudgementCard(
            title: "위험한 점",
            headline: "PER \(String(format: "%.1fx", pe))",
            detail: "성장률과 마진이 높은 밸류에이션을 정당화하는지 확인하세요.",
            systemImage: "scale.3d",
            color: AppTheme.warning
        )
    }
    if let current = info?.currentPrice, let low = info?.week52Low, let high = info?.week52High, high > low {
        let position = min(max((current - low) / (high - low), 0), 1)
        if position >= 0.75 {
            return JudgementCard(
                title: "위험한 점",
                headline: "52주 고점권 \(String(format: "%.0f%%", position * 100))",
                detail: "추격 진입보다 추세 지속과 지지선 확인이 먼저입니다.",
                systemImage: "chart.line.uptrend.xyaxis",
                color: AppTheme.warning
            )
        }
    }
    return JudgementCard(
        title: "위험한 점",
        headline: "뚜렷한 경고 없음",
        detail: "가격 급등, 부채, 밸류에이션 부담은 재무 탭에서 계속 확인하세요.",
        systemImage: "checkmark.seal",
        color: AppTheme.quality
    )
}

private func conditionJudgementCard(info: StockInfo?, metrics: [StaticMetric], signals: [InvestmentSignal]) -> JudgementCard {
    if info?.peRatio == nil && info?.forwardPe == nil {
        return JudgementCard(
            title: "확인 조건",
            headline: "PER 공백",
            detail: valuationUnavailableReason(info),
            systemImage: "questionmark.circle",
            color: AppTheme.secondaryText
        )
    }
    if let scoreMetric = metrics.first(where: { scoreMetricLabels.contains(normalizedMetricLabel($0.label)) && $0.value != "-" }) {
        return JudgementCard(
            title: "확인 조건",
            headline: "점수 근거 분해",
            detail: "\(scoreMetric.label)가 Value, Quality, Momentum 중 어디서 나왔는지 아래 근거와 비교하세요.",
            systemImage: "slider.horizontal.3",
            color: AppTheme.accent
        )
    }
    if let firstSignal = signals.first {
        return JudgementCard(
            title: "확인 조건",
            headline: "주의 신호 확인",
            detail: "\(firstSignal.title)이 가격, 재무, 이벤트와 같은 방향인지 확인하세요.",
            systemImage: "arrow.left.arrow.right",
            color: AppTheme.accent
        )
    }
    return JudgementCard(
        title: "확인 조건",
        headline: "차트와 재무 보강",
        detail: "가격 위치, 성장성, 현금흐름을 같이 확인하면 판단 노이즈가 줄어듭니다.",
        systemImage: "checklist",
        color: AppTheme.accent
    )
}

private func eventJudgementCard(metrics: [StaticMetric], signals: [InvestmentSignal]) -> JudgementCard {
    if isEarningsEvent(metrics: metrics, signals: signals) {
        let daysText = detailMetricValue(["남은 기간", "경과일"], metrics: metrics) ?? "일정 확인"
        let dateText = detailMetricValue(["예정일", "발표일"], metrics: metrics) ?? ""
        return JudgementCard(
            title: "다음 이벤트",
            headline: "\(daysText) \(dateText)",
            detail: "실적 전후에는 가격 반응과 거래량을 기존 점수와 분리해서 보세요.",
            systemImage: "calendar.badge.clock",
            color: AppTheme.momentum
        )
    }
    return JudgementCard(
        title: "다음 이벤트",
        headline: "일정 데이터 없음",
        detail: "실적 캘린더나 뉴스에서 새 이벤트가 연결되면 여기에서 먼저 보여줍니다.",
        systemImage: "calendar",
        color: AppTheme.secondaryText
    )
}

private func detailCounterEvidenceRows(
    profile: InvestmentProfile,
    info: StockInfo?,
    metrics: [StaticMetric],
    signals: [InvestmentSignal],
    watchItem: WatchlistItem?
) -> [DetailGuardrailRow] {
    let allText = detailEvidenceText(info: info, metrics: metrics, signals: signals)
    let thesis = watchItem?.investmentThesis
    let conflicts = profile.isConfigured ? profile.avoidances.filter { profileAvoidanceMatches($0, text: allText) } : []
    let warningSignal = signals.first { signal in
        let text = "\(signal.title) \(signal.detail)"
        return text.contains("주의") || text.contains("부담") || text.contains("리스크")
    }
    var rows: [DetailGuardrailRow] = []

    if let thesis, !thesis.invalidationCondition.isEmpty {
        rows.append(.init(title: "무효 조건", detail: thesis.invalidationCondition, icon: .x, color: AppTheme.warning))
    } else {
        rows.append(.init(
            title: "무효 조건 없음",
            detail: "언제 이 생각을 버릴지 정하지 않으면 좋은 뉴스만 보게 됩니다. 관심 가설에 틀렸다고 볼 조건을 먼저 남기세요.",
            icon: .triangleAlert,
            color: AppTheme.warning
        ))
    }

    if !conflicts.isEmpty {
        rows.append(.init(
            title: "내 회피 조건 충돌",
            detail: "\(conflicts.prefix(2).joined(separator: " · ")) 신호가 보입니다. 점수보다 회피 기준을 먼저 확인하세요.",
            icon: .shieldCheck,
            color: AppTheme.warning
        ))
    }

    if let warningSignal {
        rows.append(.init(
            title: "주의 신호",
            detail: "\(warningSignal.title): \(warningSignal.detail)",
            icon: .triangleAlert,
            color: warningSignal.color
        ))
    }

    if let priceEarningsRatio = info?.forwardPe ?? info?.peRatio, priceEarningsRatio > 35 {
        rows.append(.init(
            title: "밸류에이션 부담",
            detail: "PER \(String(format: "%.1fx", priceEarningsRatio)) 구간입니다. 성장률과 마진이 같이 받쳐주는지 비교 후보와 나란히 보세요.",
            icon: .trendingUp,
            color: AppTheme.warning
        ))
    }

    if let change = info?.dailyChangePct, change.isFinite, abs(change) >= 0.04 {
        rows.append(.init(
            title: "가격 급변",
            detail: "최근 변동 \(pct(change))입니다. 오늘의 움직임이 가설을 바꾸는지, 단순 소음인지 분리하세요.",
            icon: change >= 0 ? .trendingUp : .trendingDown,
            color: change >= 0 ? AppTheme.warning : AppTheme.negative
        ))
    }

    if rows.isEmpty {
        rows.append(.init(
            title: "뚜렷한 경고는 낮음",
            detail: "그래도 가격 위치, 실적 이벤트, 비교 후보를 확인한 뒤 관심 가설을 유지할지 정하세요.",
            icon: .shieldCheck,
            color: AppTheme.quality
        ))
    }

    return Array(rows.prefix(4))
}

private func detailNoBuyFirstRows(
    profile: InvestmentProfile,
    name: String,
    info: StockInfo?,
    metrics: [StaticMetric],
    signals: [InvestmentSignal],
    watchItem: WatchlistItem?,
    isCompared: Bool,
    currency: String,
    isETF: Bool
) -> [NoBuyFirstRowModel] {
    let counter = detailCounterEvidenceRows(
        profile: profile,
        info: info,
        metrics: metrics,
        signals: signals,
        watchItem: watchItem
    ).first ?? DetailGuardrailRow(
        title: "뚜렷한 경고는 낮음",
        detail: "그래도 가격 위치, 실적 이벤트, 비교 후보를 확인한 뒤 관심 가설을 유지할지 정하세요.",
        icon: .shieldCheck,
        color: AppTheme.quality
    )
    let stillWorth = detailStillWorthReason(info: info, metrics: metrics, signals: signals, isETF: isETF)
    let checkNow = detailOneThingToCheck(
        name: name,
        info: info,
        metrics: metrics,
        signals: signals,
        watchItem: watchItem,
        isCompared: isCompared,
        currency: currency,
        isETF: isETF
    )
    let personal = personalizedStockInterpretation(
        profile: profile,
        name: name,
        info: info,
        metrics: metrics,
        signals: signals
    )
    return [
        NoBuyFirstRowModel(
            label: "이 종목을 보면 안 되는 이유",
            title: counter.title,
            detail: counter.detail,
            icon: counter.icon,
            color: counter.color
        ),
        NoBuyFirstRowModel(
            label: "그래도 볼 만한 이유",
            title: stillWorth.title,
            detail: stillWorth.detail,
            icon: stillWorth.icon,
            color: stillWorth.color
        ),
        NoBuyFirstRowModel(
            label: "지금 확인해야 할 한 가지",
            title: checkNow.title,
            detail: checkNow.detail,
            icon: checkNow.icon,
            color: checkNow.color
        ),
        NoBuyFirstRowModel(
            label: "내 성향 기준 결론",
            title: personal.headline,
            detail: personal.action,
            icon: .target,
            color: personal.color
        )
    ]
}

private func detailStillWorthReason(
    info: StockInfo?,
    metrics: [StaticMetric],
    signals: [InvestmentSignal],
    isETF: Bool
) -> NoBuyFirstRowModel {
    if let signal = signals.first(where: { signal in
        let text = "\(signal.title) \(signal.detail)"
        return !text.contains("주의") && !text.contains("부담") && !text.contains("리스크")
    }) {
        return NoBuyFirstRowModel(
            label: "",
            title: signal.title,
            detail: signal.detail,
            icon: .lightbulb,
            color: signal.color
        )
    }
    if let scoreMetric = metrics.first(where: { scoreMetricLabels.contains(normalizedMetricLabel($0.label)) }) {
        return NoBuyFirstRowModel(
            label: "",
            title: "\(scoreMetric.label) \(scoreMetric.value)",
            detail: "점수는 출발점일 뿐입니다. 같은 섹터 후보와 비교할 때만 의미 있게 보세요.",
            icon: .barChart3,
            color: AppTheme.accent
        )
    }
    if isETF, let type = detailMetricValue(["테마", "유형"], metrics: metrics) {
        return NoBuyFirstRowModel(
            label: "",
            title: "\(type) 노출",
            detail: "ETF는 개별 기업보다 추종 대상, 비용, 구성 비중이 내 목적과 맞는지 확인할 수 있습니다.",
            icon: .pieChart,
            color: AppTheme.accent
        )
    }
    if let growth = info?.revenueGrowth, growth.isFinite, growth > 0 {
        return NoBuyFirstRowModel(
            label: "",
            title: "성장률 \(pct(growth))",
            detail: "성장 근거는 있습니다. 다만 가격 부담과 지속 가능성을 같이 확인해야 합니다.",
            icon: .trendingUp,
            color: AppTheme.quality
        )
    }
    return NoBuyFirstRowModel(
        label: "",
        title: "비교 후보로만 유지",
        detail: "단독 결론보다 비교 바구니에 넣고 상대 매력과 주의 신호를 같이 보세요.",
        icon: .gitCompare,
        color: AppTheme.accent
    )
}

private func detailOneThingToCheck(
    name: String,
    info: StockInfo?,
    metrics: [StaticMetric],
    signals: [InvestmentSignal],
    watchItem: WatchlistItem?,
    isCompared: Bool,
    currency: String,
    isETF: Bool
) -> NoBuyFirstRowModel {
    if !isCompared {
        return NoBuyFirstRowModel(
            label: "",
            title: "비교 후보 1개 추가",
            detail: "\(name)만 보고 판단하지 말고 비슷한 후보와 나란히 놓은 뒤 우선순위를 정하세요.",
            icon: .gitCompare,
            color: AppTheme.warning
        )
    }
    if watchItem?.investmentThesis.invalidationCondition.isEmpty != false {
        return NoBuyFirstRowModel(
            label: "",
            title: "무효 조건 적기",
            detail: "무엇이 깨지면 이 종목을 그만 볼지 먼저 정해야 좋은 뉴스만 보는 실수를 줄일 수 있습니다.",
            icon: .edit,
            color: AppTheme.warning
        )
    }
    if let row = detailActionPlanRows(info: info, metrics: metrics, signals: signals, currency: currency, isETF: isETF).first {
        return NoBuyFirstRowModel(
            label: "",
            title: row.title,
            detail: row.detail,
            icon: lucideIcon(forSystemSymbol: row.systemImage),
            color: row.color
        )
    }
    return NoBuyFirstRowModel(
        label: "",
        title: "다음 실적 또는 가격 조건",
        detail: "새 데이터가 나올 때까지 결론을 서두르지 말고 확인 조건이 충족되는지만 추적하세요.",
        icon: .calendarClock,
        color: AppTheme.accent
    )
}

private func detailMistakeCoachRows(
    profile: InvestmentProfile,
    name: String,
    info: StockInfo?,
    metrics: [StaticMetric],
    signals: [InvestmentSignal],
    watchItem: WatchlistItem?,
    isCompared: Bool
) -> [DetailGuardrailRow] {
    let thesis = watchItem?.investmentThesis
    var rows: [DetailGuardrailRow] = []

    if !isCompared {
        rows.append(.init(
            title: "단독 판단 방지",
            detail: "\(name)만 보지 말고 2~4개 후보를 비교한 뒤 우선순위를 정하세요.",
            icon: .gitCompare,
            color: AppTheme.warning
        ))
    }
    if thesis == nil || thesis?.isEmpty == true {
        rows.append(.init(
            title: "관심 이유 비어 있음",
            detail: "왜 보는지, 무엇이 바뀌면 생각을 고칠지 기록해야 나중에 판단을 복기할 수 있습니다.",
            icon: .edit,
            color: AppTheme.warning
        ))
    } else if let thesis, thesis.quality.percent < 80 {
        rows.append(.init(
            title: "가설 미완성",
            detail: "\(thesis.quality.missingFields.prefix(2).joined(separator: " · ")) 항목을 채우면 홈 브리핑의 우선순위가 더 정확해집니다.",
            icon: .lightbulb,
            color: AppTheme.warning
        ))
    }
    if let change = info?.dailyChangePct,
       change.isFinite,
       change > 0.04,
       profile.avoidances.contains(where: { $0.contains("급등락") }) || profile.riskTolerance.contains("보수") {
        rows.append(.init(
            title: "추격매수 주의",
            detail: "상승폭 \(pct(change))가 내 위험 기준과 충돌할 수 있습니다. 확인 조건 전에는 관찰로 남기세요.",
            icon: .trendingUp,
            color: AppTheme.warning
        ))
    }
    if profile.isConfigured && profile.completionPercent < 100 {
        rows.append(.init(
            title: "행동 원칙 보강",
            detail: "성향 진단을 더 채우면 홈의 소음 필터와 상세 기준 체크가 더 선명해집니다.",
            icon: .slidersHorizontal,
            color: AppTheme.accent
        ))
    }
    if rows.isEmpty {
        rows.append(.init(
            title: "오늘의 원칙",
            detail: "비교 결과, 무효 조건, 관찰 기간이 모두 맞을 때만 다음 행동으로 넘어가세요.",
            icon: .shieldCheck,
            color: AppTheme.quality
        ))
    }
    return Array(rows.prefix(3))
}

private func detailEvidenceText(info: StockInfo?, metrics: [StaticMetric], signals: [InvestmentSignal]) -> String {
    let metricText = metrics.map { "\($0.label) \($0.value)" }.joined(separator: " ")
    let signalText = signals.map { "\($0.title) \($0.detail)" }.joined(separator: " ")
    let infoText = [
        info?.forwardPe.map { "per \($0)" },
        info?.peRatio.map { "per \($0)" },
        info?.debtToEquity.map { "debt \($0)" },
        info?.revenueGrowth.map { "growth \($0)" },
        info?.profitMargin.map { "margin \($0)" }
    ].compactMap { $0 }.joined(separator: " ")
    return "\(metricText) \(signalText) \(infoText)".lowercased()
}

private func stockDecisionPills(info: StockInfo?, metrics: [StaticMetric], currency: String, isETF: Bool = false) -> [DecisionPill] {
    var rows: [DecisionPill] = []
    if let current = info?.currentPrice, let prev = info?.prevClose, prev != 0 {
        let change = current / prev - 1
        rows.append(DecisionPill(
            title: "당일 흐름",
            value: pct(change),
            detail: signedPx(current - prev, currency: currency),
            color: change >= 0 ? .red : .blue
        ))
    }
    if let scoreMetric = metrics.first(where: { scoreMetricLabels.contains(normalizedMetricLabel($0.label)) }) {
        rows.append(DecisionPill(title: "모델 점수", value: scoreMetric.value, detail: scoreMetric.label, color: scoreMetric.color))
    }
    if isETF {
        if let fee = detailMetricValue(["총보수"], metrics: metrics) {
            rows.append(DecisionPill(title: "총보수", value: fee, detail: "ETF 비용", color: AppTheme.accent))
        }
        if let aum = detailMetricValue(["AUM"], metrics: metrics) {
            rows.append(DecisionPill(title: "AUM", value: aum, detail: "운용 규모", color: AppTheme.primaryText))
        }
        return Array(rows.prefix(4))
    }
    if let growthMetric = metrics.first(where: { normalizedMetricLabel($0.label).contains("growth") || normalizedMetricLabel($0.label).contains("성장") }) {
        rows.append(DecisionPill(title: "성장성", value: growthMetric.value, detail: growthMetric.label, color: growthMetric.color))
    }
    if let roicMetric = metrics.first(where: { normalizedMetricLabel($0.label).contains("roic") }) {
        rows.append(DecisionPill(title: "수익성", value: roicMetric.value, detail: "ROIC", color: roicMetric.color))
    }
    if let pe = info?.forwardPe ?? info?.peRatio {
        rows.append(DecisionPill(title: "PER", value: String(format: "%.1fx", pe), detail: info?.forwardPe == nil ? "Trailing" : "Forward", color: AppTheme.primaryText))
    }
    return Array(rows.prefix(4))
}

private func decisionSummaryText(info: StockInfo?, signals: [InvestmentSignal], metrics: [StaticMetric], isETF: Bool = false) -> String {
    if let firstSignal = signals.first {
        return "\(firstSignal.title): \(firstSignal.detail)"
    }
    if isETF {
        if let theme = detailMetricValue(["테마", "유형"], metrics: metrics) {
            return "\(theme) ETF · 가격 차트, 구성 비중, 총보수와 AUM을 중심으로 확인하세요."
        }
        return "ETF는 기업 재무보다 추종 지수, 구성 종목, 비용, 가격 흐름을 중심으로 판단합니다."
    }
    if let scoreMetric = metrics.first(where: { scoreMetricLabels.contains(normalizedMetricLabel($0.label)) }) {
        return "\(scoreMetric.label) \(scoreMetric.value)를 기준으로 후보군 내 상대 매력을 먼저 확인하고, 차트와 밸류에이션으로 진입 타이밍을 보완합니다."
    }
    if info?.currentPrice != nil {
        return "현재가, 52주 위치, 밸류에이션을 함께 확인해 가격 위치와 기본 체력을 점검합니다."
    }
    return "상세 데이터가 도착하면 가격, 밸류에이션, 성장성, 리스크 순서로 판단 근거를 정리합니다."
}

private func detailActionPlanRows(info: StockInfo?, metrics: [StaticMetric], signals: [InvestmentSignal], currency: String, isETF: Bool = false) -> [DetailActionPlanRow] {
    var rows: [DetailActionPlanRow] = []
    if isETF {
        if let firstSignal = signals.first {
            rows.append(DetailActionPlanRow(
                title: "ETF 성격 확인",
                detail: "\(firstSignal.title)을 기준으로 추종 대상과 사용 목적을 먼저 확인하세요.",
                systemImage: firstSignal.systemImage,
                color: firstSignal.color
            ))
        }
        if let current = info?.currentPrice, let low = info?.week52Low, let high = info?.week52High, high > low {
            let position = min(max((current - low) / (high - low), 0), 1)
            rows.append(DetailActionPlanRow(
                title: position >= 0.75 ? "고점권 매수 주의" : position <= 0.25 ? "저점권 반등 확인" : "가격 위치 확인",
                detail: "현재가는 52주 범위의 \(String(format: "%.0f%%", position * 100)) 지점입니다. 추종 지수 흐름과 함께 보세요.",
                systemImage: "chart.line.uptrend.xyaxis",
                color: position >= 0.75 ? AppTheme.warning : AppTheme.accent
            ))
        } else {
            rows.append(DetailActionPlanRow(
                title: "차트 기간 비교",
                detail: "1개월부터 5년까지 수익률과 변동성을 바꿔 보며 진입 구간을 판단하세요.",
                systemImage: "chart.xyaxis.line",
                color: AppTheme.accent
            ))
        }
        if let fee = detailMetricValue(["총보수"], metrics: metrics) {
            rows.append(DetailActionPlanRow(
                title: "비용 비교",
                detail: "총보수 \(fee)를 같은 지수 또는 같은 테마 ETF와 비교하세요.",
                systemImage: "percent",
                color: AppTheme.quality
            ))
        }
        return Array(rows.prefix(3))
    }
    if let firstSignal = signals.first {
        rows.append(DetailActionPlanRow(
            title: "핵심 신호 먼저 확인",
            detail: "\(firstSignal.title)을 기준으로 핵심 신호와 가격 위치를 함께 확인하세요.",
            systemImage: firstSignal.systemImage,
            color: firstSignal.color
        ))
    } else if let scoreMetric = metrics.first(where: { scoreMetricLabels.contains(normalizedMetricLabel($0.label)) }) {
        rows.append(DetailActionPlanRow(
            title: "모델 점수 확인",
            detail: "\(scoreMetric.label) \(scoreMetric.value)가 어떤 팩터에서 나온 값인지 아래 근거를 먼저 확인하세요.",
            systemImage: "slider.horizontal.3",
            color: scoreMetric.color
        ))
    }

    if let current = info?.currentPrice, let low = info?.week52Low, let high = info?.week52High, high > low {
        let position = min(max((current - low) / (high - low), 0), 1)
        let title = position >= 0.75 ? "고점권 진입 타이밍 주의" : position <= 0.25 ? "저점권 반등 조건 확인" : "가격 위치 중립"
        let detail = "현재가는 52주 범위의 \(String(format: "%.0f%%", position * 100)) 지점입니다. 차트에서 추세와 지지선을 같이 보세요."
        rows.append(DetailActionPlanRow(
            title: title,
            detail: detail,
            systemImage: "chart.line.uptrend.xyaxis",
            color: position >= 0.75 ? AppTheme.warning : AppTheme.accent
        ))
    } else if info?.currentPrice != nil {
        rows.append(DetailActionPlanRow(
            title: "가격 기준 보강 필요",
            detail: "현재가는 있으나 52주 범위가 부족합니다. 차트 기간을 바꿔 가격 위치를 직접 확인하세요.",
            systemImage: "chart.xyaxis.line",
            color: AppTheme.secondaryText
        ))
    }

    if let pe = info?.forwardPe ?? info?.peRatio {
        rows.append(DetailActionPlanRow(
            title: pe >= 35 ? "밸류에이션 부담 확인" : "밸류에이션 비교",
            detail: "PER \(String(format: "%.1fx", pe))입니다. 성장률과 마진이 이 배수를 정당화하는지 비교하세요.",
            systemImage: "scale.3d",
            color: pe >= 35 ? AppTheme.warning : AppTheme.primaryText
        ))
    } else {
        rows.append(DetailActionPlanRow(
            title: "PER 공백 확인",
            detail: valuationUnavailableReason(info),
            systemImage: "questionmark.circle",
            color: AppTheme.secondaryText
        ))
    }

    if rows.isEmpty {
        rows.append(DetailActionPlanRow(
            title: "상세 데이터 대기",
            detail: "가격, 팩터, 실적 데이터가 도착하면 확인 순서를 자동으로 정리합니다.",
            systemImage: "hourglass",
            color: AppTheme.secondaryText
        ))
    }
    return Array(rows.prefix(3))
}

private func earningsEventPlanRows(metrics: [StaticMetric], signals: [InvestmentSignal]) -> [DetailActionPlanRow] {
    guard isEarningsEvent(metrics: metrics, signals: signals) else { return [] }
    let daysText = detailMetricValue(["남은 기간", "경과일"], metrics: metrics)
    let dateText = detailMetricValue(["예정일", "발표일"], metrics: metrics)
    let isPreEvent = metrics.contains(where: { $0.label == "예정일" })
        || daysText?.contains("후") == true
        || daysText?.contains("오늘") == true
    var rows: [DetailActionPlanRow] = []

    if isPreEvent {
        rows.append(DetailActionPlanRow(
            title: "발표 전",
            detail: "\(daysText ?? "예정일") \(dateText ?? "") 기준으로 포지션 크기, 변동성, 컨센서스 변화를 먼저 확인하세요.",
            systemImage: "calendar",
            color: AppTheme.warning
        ))
        rows.append(DetailActionPlanRow(
            title: "발표 직후",
            detail: "EPS보다 매출, 마진, 가이던스, 거래량 반응을 함께 보고 하루짜리 급등락과 추세 변화를 분리하세요.",
            systemImage: "bolt.horizontal",
            color: AppTheme.momentum
        ))
    } else {
        rows.append(DetailActionPlanRow(
            title: "발표 후 추적",
            detail: "\(daysText ?? "발표 후") 구간입니다. 실적 서프라이즈가 가격과 거래량에 계속 반영되는지 확인하세요.",
            systemImage: "waveform.path.ecg",
            color: AppTheme.momentum
        ))
    }

    rows.append(DetailActionPlanRow(
        title: "다음 판단",
        detail: "실적 이벤트 전후에는 기존 모델 점수와 가격 반응이 같은 방향인지 확인한 뒤 관심 유지 여부를 정하세요.",
        systemImage: "checkmark.seal",
        color: AppTheme.accent
    ))
    return rows
}

private func isEarningsEvent(metrics: [StaticMetric], signals: [InvestmentSignal]) -> Bool {
    let metricLabels = metrics.map { normalizedMetricLabel($0.label) }
    let signalText = signals.map { "\($0.title) \($0.detail)" }.joined(separator: " ")
    return metricLabels.contains { label in
        ["예정일", "남은기간", "발표일", "경과일", "eps"].contains(label)
    } || signalText.contains("실적") || signalText.localizedCaseInsensitiveContains("earnings")
}

private func detailMetricValue(_ labels: [String], metrics: [StaticMetric]) -> String? {
    let normalized = Set(labels.map(normalizedMetricLabel))
    return metrics.first {
        normalized.contains(normalizedMetricLabel($0.label))
            && !$0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && $0.value != "-"
    }?.value
}

private func scoreRationaleRows(metrics: [StaticMetric], signals: [InvestmentSignal]) -> [ScoreRationaleRow] {
    var rows = metrics
        .filter { metric in
            let label = normalizedMetricLabel(metric.label)
            return !metric.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && metric.value != "-"
                && !["시장", "섹터", "분류", "상태"].contains(metric.label)
                && !label.contains("시가총액")
        }
        .prefix(5)
        .map { metric in
            ScoreRationaleRow(
                title: metric.label,
                value: metric.value,
                detail: scoreMetricExplanation(metric),
                termLabel: metric.label,
                color: metric.color
            )
        }
    if rows.isEmpty {
        rows = signals.prefix(3).map {
            ScoreRationaleRow(title: $0.title, value: "근거", detail: $0.detail, termLabel: $0.title, color: $0.color)
        }
    }
    return Array(rows)
}

private func scoreMetricExplanation(_ metric: StaticMetric) -> String {
    let label = normalizedMetricLabel(metric.label)
    if label.contains("기대") || label.contains("return") {
        return "모델이 보는 기대수익 또는 발표 후 수익 반응으로 후보 우선순위에 직접 반영됩니다."
    }
    if label.contains("score") || label.contains("점수") {
        return "여러 팩터를 합산한 상대 점수라 후보군 안에서의 순위를 판단하는 핵심 축입니다."
    }
    if label.contains("roic") || label.contains("roe") {
        return "자본 효율성과 수익성을 보여줘 퀄리티 팩터에 반영됩니다."
    }
    if label.contains("growth") || label.contains("성장") {
        return "매출 또는 이익 성장 흐름이 좋아질수록 성장 팩터에 유리합니다."
    }
    if label.contains("margin") || label.contains("마진") {
        return "마진은 사업의 체력과 가격 결정력을 보는 보조 근거입니다."
    }
    return "후보 선정 시 함께 비교하는 보조 지표입니다."
}

private func missingStockDataReasons(info: StockInfo?, metrics: [StaticMetric], isETF: Bool = false) -> [MissingDataReason] {
    var reasons: [MissingDataReason] = []
    if isETF {
        if info?.currentPrice == nil {
            reasons.append(MissingDataReason(title: "가격 데이터 대기", detail: "ETF 현재가가 도착하면 차트와 수익률을 바로 확인할 수 있습니다."))
        }
        if info?.week52Low == nil || info?.week52High == nil {
            reasons.append(MissingDataReason(title: "가격 범위 데이터 대기", detail: "52주 범위가 도착하면 고점권인지 저점권인지 표시합니다."))
        }
        return reasons
    }
    if info?.peRatio == nil && info?.forwardPe == nil {
        reasons.append(MissingDataReason(title: "PER 계산 불가", detail: valuationUnavailableReason(info)))
    }
    if info?.priceToBook == nil {
        reasons.append(MissingDataReason(title: "PBR 없음", detail: "순자산 또는 주가 기준 데이터가 아직 상세 응답에 포함되지 않았습니다."))
    }
    if info?.returnOnEquity == nil && !metrics.contains(where: { normalizedMetricLabel($0.label).contains("roe") || normalizedMetricLabel($0.label).contains("roic") }) {
        reasons.append(MissingDataReason(title: "수익성 보강 필요", detail: "ROE/ROIC 계열 지표가 부족해 퀄리티 판단은 제한적입니다."))
    }
    if info?.totalRevenue == nil && info?.revenueGrowth == nil {
        reasons.append(MissingDataReason(title: "성장 데이터 부족", detail: "매출 또는 성장률이 없어서 성장성 판단은 후보 점수/기존 팩터에 의존합니다."))
    }
    if info?.freeCashflow == nil {
        reasons.append(MissingDataReason(title: "현금흐름 없음", detail: "FCF가 없으면 이익의 현금 전환 품질을 앱 안에서 바로 검증하기 어렵습니다."))
    }
    return reasons
}

private struct InvestmentProfileFitRow: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let icon: LucideIcon
    let color: Color
}

private func investmentProfileFitRows(
    profile: InvestmentProfile,
    metrics: [StaticMetric],
    signals: [InvestmentSignal]
) -> [InvestmentProfileFitRow] {
    guard profile.isConfigured else { return [] }
    let metricText = metrics.map { "\($0.label) \($0.value)" }.joined(separator: " ").lowercased()
    let signalText = signals.map { "\($0.title) \($0.detail)" }.joined(separator: " ").lowercased()
    let allText = "\(metricText) \(signalText)"
    var rows: [InvestmentProfileFitRow] = []

    if !profile.riskTolerance.isEmpty {
        let hasWarning = allText.contains("주의") ||
            allText.contains("부담") ||
            allText.contains("risk")
        rows.append(.init(
            title: "\(profile.riskTolerance) 기준",
            detail: hasWarning ? "주의 신호가 있어 진입보다 확인 조건을 먼저 잡는 편이 맞습니다." : "현재 핵심 신호에는 큰 경고가 적어 다음 확인 지표로 넘어갈 수 있습니다.",
            icon: hasWarning ? .triangleAlert : .shieldCheck,
            color: hasWarning ? AppTheme.warning : .green
        ))
    }

    if !profile.style.isEmpty {
        let style = profile.style
        let matched = profileStyleMatches(style, text: allText)
        rows.append(.init(
            title: "\(style) 관점",
            detail: matched ? "선호 스타일과 맞는 근거가 일부 보입니다. 반대 지표까지 같이 비교하세요." : "선호 스타일과 직접 맞는 근거가 약합니다. 점수보다 핵심 지표를 먼저 확인하세요.",
            icon: matched ? .target : .slidersHorizontal,
            color: matched ? AppTheme.accent : AppTheme.secondaryText
        ))
    }

    if !profile.avoidances.isEmpty {
        let conflicts = profile.avoidances.filter { avoidance in
            profileAvoidanceMatches(avoidance, text: allText)
        }
        rows.append(.init(
            title: "회피 신호",
            detail: conflicts.isEmpty ? "설정한 회피 조건과 직접 충돌하는 신호는 아직 뚜렷하지 않습니다." : "\(conflicts.prefix(2).joined(separator: ", ")) 조건이 보입니다. 투자 가설의 무효 조건으로 남겨두세요.",
            icon: conflicts.isEmpty ? .shieldCheck : .triangleAlert,
            color: conflicts.isEmpty ? .green : AppTheme.warning
        ))
    }

    if !profile.horizon.isEmpty {
        rows.append(.init(
            title: "\(profile.horizon) 관찰",
            detail: profile.horizon.contains("1개월") ? "짧은 기간이면 가격 위치와 실적 이벤트를 먼저 확인하세요." : "긴 기간이면 성장률, 마진, 재무 리스크가 함께 유지되는지 보세요.",
            icon: .calendarClock,
            color: AppTheme.accent
        ))
    }

    return Array(rows.prefix(3))
}

private func profileStyleMatches(_ style: String, text: String) -> Bool {
    if style.contains("성장") { return text.contains("성장") || text.contains("growth") || text.contains("매출") }
    if style.contains("가치") { return text.contains("value") || text.contains("저평가") || text.contains("per") || text.contains("pbr") }
    if style.contains("배당") { return text.contains("배당") || text.contains("dividend") }
    if style.contains("퀄리티") { return text.contains("roic") || text.contains("roe") || text.contains("마진") || text.contains("quality") }
    if style.contains("모멘텀") { return text.contains("모멘텀") || text.contains("거래량") || text.contains("surge") || text.contains("momentum") }
    return false
}

private func profileAvoidanceMatches(_ avoidance: String, text: String) -> Bool {
    if avoidance.contains("급등락") { return text.contains("급변") || text.contains("거래량") || text.contains("vol") || text.contains("momentum") }
    if avoidance.contains("적자") { return text.contains("적자") || text.contains("음수") || text.contains("negative") }
    if avoidance.contains("고평가") { return text.contains("고평가") || text.contains("per") || text.contains("밸류") }
    if avoidance.contains("부채") { return text.contains("부채") || text.contains("debt") }
    if avoidance.contains("거래량") { return text.contains("거래량") || text.contains("volume") }
    return false
}

func valuationUnavailableReason(_ info: StockInfo?) -> String {
    if info == nil { return "상세 응답 대기" }
    if info?.currentPrice == nil { return "시세 데이터 부족" }
    if info?.priceToBook != nil { return "순이익/EPS 부족" }
    if info?.profitMargin != nil, let margin = info?.profitMargin, margin <= 0 {
        return "적자 또는 순이익률 음수"
    }
    return "EPS/순이익 데이터 부족"
}

private let scoreMetricLabels: Set<String> = [
    "factorscore", "smallcapscore", "score", "signal", "최종점수", "종합점수", "시그널"
]

private func normalizedMetricLabel(_ value: String) -> String {
    value
        .lowercased()
        .replacingOccurrences(of: " ", with: "")
        .replacingOccurrences(of: "_", with: "")
        .replacingOccurrences(of: "-", with: "")
}
