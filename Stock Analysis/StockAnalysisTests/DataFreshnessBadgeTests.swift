import XCTest
@testable import Stock_Analysis

final class DataFreshnessBadgeTests: XCTestCase {
    private var now: Date {
        ISO8601DateFormatter().date(from: "2026-05-20T12:00:00Z") ?? Date(timeIntervalSince1970: 1_779_278_400)
    }

    func testNoSourceAndNoTimestampIsHidden() {
        XCTAssertNil(dataFreshnessPresentation(source: nil, updatedAt: nil, now: now))
    }

    func testUnknownSourceIsHidden() {
        XCTAssertNil(dataFreshnessPresentation(source: "fallback", updatedAt: "2026-05-20T12:00:00Z", now: now))
    }

    func testStorageJustNowShowsFreshText() {
        let display = dataFreshnessPresentation(source: "storage", updatedAt: "2026-05-20T11:59:30Z", now: now)
        XCTAssertEqual(display?.text, "방금 전")
        XCTAssertEqual(display?.tone, .fresh)
    }

    func testStorageWithinTenMinutesStaysFresh() {
        let display = dataFreshnessPresentation(source: "storage", updatedAt: "2026-05-20T11:55:00Z", now: now)
        XCTAssertEqual(display?.text, "5분 전")
        XCTAssertEqual(display?.tone, .fresh)
    }

    func testStorageOverTenMinutesIsDelayed() {
        let display = dataFreshnessPresentation(source: "storage", updatedAt: "2026-05-20T11:39:00Z", now: now)
        XCTAssertEqual(display?.text, "21분 전")
        XCTAssertEqual(display?.tone, .delayed)
    }

    func testStorageOverOneHourIsStale() {
        let display = dataFreshnessPresentation(source: "storage", updatedAt: "2026-05-20T10:55:00Z", now: now)
        XCTAssertEqual(display?.text, "1시간 전")
        XCTAssertEqual(display?.tone, .stale)
    }

    func testStorageOverOneDayUsesDayText() {
        let display = dataFreshnessPresentation(source: "storage", updatedAt: "2026-05-18T09:00:00Z", now: now)
        XCTAssertEqual(display?.text, "2일 전")
        XCTAssertEqual(display?.tone, .stale)
    }

    func testStorageSnapshotShowsPartialData() {
        let display = dataFreshnessPresentation(source: "storage_snapshot", updatedAt: nil, now: now)
        XCTAssertEqual(display?.text, "부분 데이터")
        XCTAssertEqual(display?.tone, .partial)
    }

    func testStorageWithoutParseableTimestampIsHidden() {
        XCTAssertNil(dataFreshnessPresentation(source: "storage", updatedAt: "not-a-date", now: now))
    }
}
