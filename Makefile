PYTHON := $(if $(wildcard .venv/bin/python),.venv/bin/python,python)
PIP := $(if $(wildcard .venv/bin/pip),.venv/bin/pip,python -m pip)
KR_LOCAL_LIMIT ?= 60
KR_LOCAL_KOSPI_LIMIT ?= 30
KR_LOCAL_KOSDAQ_LIMIT ?= 30
KR_LOCAL_DELAY ?= 0.08
KR_LOCAL_PORTFOLIO_SIZE ?= 10
KR_LOCAL_SMALLCAP_SIZE ?= 20
KR_LOCAL_EXTRA ?=
KR_RANK_HEALTH_EXTRA ?=
US_LOCAL_LIMIT ?= 80
US_LOCAL_DELAY ?= 0.05
US_LOCAL_PORTFOLIO_SIZE ?= 10
US_LOCAL_SMALLCAP_SIZE ?= 20
US_LOCAL_EXTRA ?=
LOCAL_DATABASE_URL ?= postgresql://quantbridge:quantbridge@localhost:5432/quantbridge
LOCAL_STORAGE_ENV := QUANT_ENABLE_POSTGRES=true QUANT_DATABASE_URL=$(LOCAL_DATABASE_URL)
POLICY_RANKING_EXTRA ?=
MOBILE_SMOKE_EXTRA ?=
ANDROID_EMULATOR_SMOKE_EXTRA ?=
STAGING_ANDROID_SMOKE_EXTRA ?=

.PHONY: venv postgres server stop bootstrap warm-cache warm-sector-cache precompute-app-snapshots price-snapshots kr-rank-local kr-rank-health us-rank-local refresh-app-data-local refresh-app-data-staging backfill-snapshots quality-gates factor-policy policy-backtest repair-scored-quality policy-adjusted-rankings remediation-plan research-quality research-quality-docker research-health data-quality ops-health workspace-audit secret-audit cleanup-duplicates cleanup-duplicates-apply export-public-dry export-public ci-local regen-mobile-clients ios-build ios-test android-build mobile-smoke ios-simulator-qa account-watchlist-smoke staging-url staging-readiness staging-smoke staging-android-smoke staging-android-smoke-quick staging-android-smoke-full staging-ops-health staging-status idle-staging stop-staging start-staging restart-staging delete-staging create-staging-resources sync-staging-secrets deploy-staging-local bootstrap-staging-data staging-research-quality android-device-qa android-emulator-smoke android-emulator-smoke-quick android-emulator-smoke-full linux-deploy linux-price-refresh linux-install-systemd print-ops-schedule install-ops-schedule uninstall-ops-schedule ops-schedule-status print-price-schedule install-price-schedule uninstall-price-schedule price-schedule-status print-kr-rank-schedule install-kr-rank-schedule uninstall-kr-rank-schedule kr-rank-schedule-status print-staging-ops-schedule install-staging-ops-schedule print-research-schedule install-research-schedule uninstall-research-schedule research-schedule-status configure-app-api app-smoke user-flow qa api dag-dry-run test

venv:
	python3 -m venv .venv
	$(PIP) install -r requirements.txt
	$(PIP) install -r api/requirements_api.txt
	$(PIP) install -r GitHub/my-quant-dashboard/requirements.txt

postgres:
	docker compose up -d postgres

server:
	docker compose up -d --build postgres api

stop:
	docker compose down

bootstrap:
	$(PYTHON) tools/bootstrap_storage.py

warm-cache:
	$(PYTHON) tools/warm_detail_cache.py --period 5y

warm-sector-cache:
	$(PYTHON) tools/warm_sector_cache.py

precompute-app-snapshots:
	$(PYTHON) tools/precompute_app_snapshots.py --clear-api-url http://127.0.0.1:8000

price-snapshots:
	$(PYTHON) tools/sync_price_snapshots.py

kr-rank-local:
	$(PYTHON) tools/local_kr_ranker.py --limit $(KR_LOCAL_LIMIT) --kospi-limit $(KR_LOCAL_KOSPI_LIMIT) --kosdaq-limit $(KR_LOCAL_KOSDAQ_LIMIT) --delay $(KR_LOCAL_DELAY) --portfolio-size $(KR_LOCAL_PORTFOLIO_SIZE) --smallcap-size $(KR_LOCAL_SMALLCAP_SIZE) $(KR_LOCAL_EXTRA)

kr-rank-health:
	$(PYTHON) tools/check_kr_rank_health.py $(KR_RANK_HEALTH_EXTRA)

us-rank-local:
	$(PYTHON) tools/local_us_ranker.py --limit $(US_LOCAL_LIMIT) --delay $(US_LOCAL_DELAY) --portfolio-size $(US_LOCAL_PORTFOLIO_SIZE) --smallcap-size $(US_LOCAL_SMALLCAP_SIZE) $(US_LOCAL_EXTRA)

refresh-app-data-local:
	$(MAKE) postgres
	$(LOCAL_STORAGE_ENV) $(MAKE) kr-rank-local
	$(LOCAL_STORAGE_ENV) $(MAKE) price-snapshots
	$(LOCAL_STORAGE_ENV) $(MAKE) precompute-app-snapshots
	$(LOCAL_STORAGE_ENV) $(MAKE) warm-cache
	$(LOCAL_STORAGE_ENV) $(MAKE) data-quality
	$(LOCAL_STORAGE_ENV) $(PYTHON) tools/check_kr_rank_health.py --skip-launchd $(KR_RANK_HEALTH_EXTRA)

refresh-app-data-staging:
	$(MAKE) bootstrap-staging-data
	$(MAKE) staging-research-quality
	$(PYTHON) tools/check_staging_status.py --warn-only --timeout 30

backfill-snapshots:
	$(PYTHON) tools/backfill_factor_snapshots.py --months 9

quality-gates:
	$(PYTHON) pipeline/15_signal_quality_gate.py

factor-policy:
	$(PYTHON) pipeline/16_factor_weight_policy.py

policy-backtest:
	$(PYTHON) pipeline/17_factor_policy_backtest.py

repair-scored-quality:
	$(PYTHON) tools/repair_scored_quality_schema.py

policy-adjusted-rankings:
	$(PYTHON) tools/build_policy_adjusted_rankings.py $(POLICY_RANKING_EXTRA)

remediation-plan:
	$(PYTHON) pipeline/18_factor_remediation_plan.py

research-quality:
	$(PYTHON) tools/run_research_quality.py

research-quality-docker:
	docker compose run --rm research-quality

research-health:
	$(PYTHON) tools/check_research_quality_health.py

data-quality:
	$(PYTHON) tools/check_data_quality.py

ops-health:
	$(PYTHON) tools/check_ops_health.py

workspace-audit:
	$(PYTHON) tools/audit_workspace.py

secret-audit:
	$(PYTHON) tools/check_no_local_artifacts.py

cleanup-duplicates:
	$(PYTHON) tools/cleanup_finder_duplicates.py

cleanup-duplicates-apply:
	$(PYTHON) tools/cleanup_finder_duplicates.py --apply

export-public-dry:
	$(PYTHON) tools/export_public_tree.py --dest ../quantbridge-public-clean

export-public:
	$(PYTHON) tools/export_public_tree.py --dest ../quantbridge-public-clean --apply

ci-local:
	$(PYTHON) tools/check_ci_workflows.py
	$(PYTHON) -m py_compile main_engine.py main_dag.py api/server.py api/auth.py api/auth_store.py api/runtime_state.py api/contracts/mobile_v1.py api/routers/calendar.py api/routers/etfs.py api/routers/market.py api/routers/news.py api/routers/ops.py api/routers/portfolio.py api/routers/ranking.py api/routers/research.py api/routers/risk.py api/routers/search.py api/routers/sectors.py api/routers/stock.py api/routers/system.py api/services/app_snapshots.py api/services/calendar_api.py api/services/company_names.py api/services/etf_api.py api/services/etf_insights.py api/services/market_api.py api/services/news_api.py api/services/news_changes.py api/services/news_impact.py api/services/news_internal.py api/services/news_public.py api/services/news_queries.py api/services/news_sources.py api/services/ops_api.py api/services/portfolio_api.py api/services/ranking_api.py api/services/research_api.py api/services/risk_api.py api/services/risk_reports.py api/services/search_api.py api/services/sector_api.py api/services/stock_detail.py api/services/stock_identity.py api/services/stock_quotes.py api/services/storage_frames.py api/services/system_api.py pipeline/02_macro_regime.py pipeline/scoring/common_factor_scorer.py pipeline/scoring/kr_factor_scorer.py pipeline/scoring/company_quality.py pipeline/scoring/company_quality_report.py tools/audit_workspace.py tools/build_policy_adjusted_rankings.py tools/check_ci_workflows.py tools/check_data_quality.py tools/check_kr_rank_health.py tools/check_ops_health.py tools/check_no_local_artifacts.py tools/check_research_quality_health.py tools/check_staging_status.py tools/cleanup_finder_duplicates.py tools/configure_app_api.py tools/export_public_tree.py tools/install_kr_rank_local_launchd.py tools/install_ops_health_launchd.py tools/install_price_snapshots_launchd.py tools/install_research_quality_launchd.py tools/mobile_smoke_qa.py tools/qa_android_device.py tools/qa_android_emulator_smoke.py tools/repair_scored_quality_schema.py tools/run_research_quality.py tools/sync_price_snapshots.py tools/precompute_app_snapshots.py tools/warm_sector_cache.py GitHub/my-quant-dashboard/app.py GitHub/my-quant-dashboard/data_loader.py quantbridge/config.py quantbridge/price_snapshots.py quantbridge/quality.py quantbridge/schemas.py quantbridge/storage/postgres.py quantbridge/storage/repository.py
	$(PYTHON) tools/check_no_local_artifacts.py
	$(PYTHON) -m unittest test_contracts.py test_smallcap_scoring.py test_data_quality.py test_api_auth.py test_api_ops.py test_config.py test_pipeline_imports.py test_common_factor_scorer.py test_kr_pit_backtest.py test_kr_rank_health.py test_stock_quotes.py test_company_quality.py test_company_quality_report.py test_staging_status.py test_android_emulator_smoke.py test_storage_frames.py
	$(PYTHON) main_dag.py --dry-run --no-prefect
	$(PYTHON) tools/install_ops_health_launchd.py render > /tmp/quantbridge-ops-health.plist
	$(PYTHON) tools/install_research_quality_launchd.py render > /tmp/quantbridge-research-quality.plist
	$(PYTHON) tools/install_kr_rank_local_launchd.py render > /tmp/quantbridge-kr-rank-local.plist
	$(PYTHON) -c "import plistlib,sys; [print(f'{p}: OK') if (d:=plistlib.load(open(p,'rb'))).get('Label') and d.get('ProgramArguments') else (_ for _ in ()).throw(SystemExit(f'{p}: invalid plist')) for p in sys.argv[1:]]" /tmp/quantbridge-ops-health.plist /tmp/quantbridge-research-quality.plist /tmp/quantbridge-kr-rank-local.plist
	docker compose config --quiet

regen-mobile-clients:
	bash scripts/regen-mobile-clients.sh

ios-build:
	xcodebuild -project "Stock Analysis/Stock Analysis.xcodeproj" -scheme "Stock Analysis" -configuration Debug -destination "generic/platform=iOS Simulator" CODE_SIGNING_ALLOWED=NO build

ios-test:
	xcodebuild test -project "Stock Analysis/Stock Analysis.xcodeproj" \
		-scheme "Stock Analysis" \
		-destination "platform=iOS Simulator,name=iPhone 15" \
		-enableCodeCoverage YES

android-build:
	cd android && ./gradlew :app:assembleDebug --stacktrace

mobile-smoke:
	$(PYTHON) tools/mobile_smoke_qa.py $(MOBILE_SMOKE_EXTRA)

ios-simulator-qa:
	$(PYTHON) tools/qa_ios_simulator.py

account-watchlist-smoke:
	$(PYTHON) tools/smoke_user_flow.py --skip-detail

staging-url:
	@deploy/azure/staging-url.sh

staging-readiness:
	$(PYTHON) tools/check_staging_status.py --warn-only

staging-smoke:
	$(PYTHON) tools/check_staging_status.py --smoke --warn-only

staging-android-smoke:
	$(PYTHON) tools/check_staging_status.py --smoke --android-emulator-smoke --android-profile full --timeout 30 $(STAGING_ANDROID_SMOKE_EXTRA)

staging-android-smoke-quick:
	$(PYTHON) tools/check_staging_status.py --smoke --android-emulator-smoke --android-profile quick --timeout 30 $(STAGING_ANDROID_SMOKE_EXTRA)

staging-android-smoke-full:
	$(PYTHON) tools/check_staging_status.py --smoke --android-emulator-smoke --android-profile full --timeout 30 $(STAGING_ANDROID_SMOKE_EXTRA)

staging-ops-health:
	$(PYTHON) tools/check_staging_status.py --warn-only --timeout 30

staging-status:
	deploy/azure/staging-control.sh status

idle-staging:
	deploy/azure/staging-control.sh idle

stop-staging:
	deploy/azure/staging-control.sh stop

start-staging:
	deploy/azure/staging-control.sh start

restart-staging:
	deploy/azure/staging-control.sh restart

delete-staging:
	deploy/azure/staging-control.sh delete

create-staging-resources:
	deploy/azure/create-staging-resources.sh

sync-staging-secrets:
	deploy/azure/sync-staging-secrets.sh

deploy-staging-local:
	deploy/azure/deploy-api-local.sh

bootstrap-staging-data:
	deploy/azure/bootstrap-staging-data.sh

staging-research-quality:
	deploy/azure/run-staging-research-quality.sh

android-device-qa:
	$(PYTHON) tools/qa_android_device.py

android-emulator-smoke:
	$(PYTHON) tools/qa_android_emulator_smoke.py --profile quick $(ANDROID_EMULATOR_SMOKE_EXTRA)

android-emulator-smoke-quick:
	$(PYTHON) tools/qa_android_emulator_smoke.py --profile quick $(ANDROID_EMULATOR_SMOKE_EXTRA)

android-emulator-smoke-full:
	$(PYTHON) tools/qa_android_emulator_smoke.py --profile full $(ANDROID_EMULATOR_SMOKE_EXTRA)

linux-deploy:
	bash deploy/linux/deploy.sh

linux-price-refresh:
	bash deploy/linux/run-price-refresh.sh

linux-install-systemd:
	sudo bash deploy/linux/install-systemd.sh

print-ops-schedule:
	@$(PYTHON) tools/install_ops_health_launchd.py render

install-ops-schedule:
	$(PYTHON) tools/install_ops_health_launchd.py install

uninstall-ops-schedule:
	$(PYTHON) tools/install_ops_health_launchd.py uninstall

ops-schedule-status:
	$(PYTHON) tools/install_ops_health_launchd.py status

print-price-schedule:
	@$(PYTHON) tools/install_price_snapshots_launchd.py render

install-price-schedule:
	$(PYTHON) tools/install_price_snapshots_launchd.py install --run-now

uninstall-price-schedule:
	$(PYTHON) tools/install_price_snapshots_launchd.py uninstall

price-schedule-status:
	$(PYTHON) tools/install_price_snapshots_launchd.py status

print-kr-rank-schedule:
	@$(PYTHON) tools/install_kr_rank_local_launchd.py render

install-kr-rank-schedule:
	$(PYTHON) tools/install_kr_rank_local_launchd.py install --run-now

uninstall-kr-rank-schedule:
	$(PYTHON) tools/install_kr_rank_local_launchd.py uninstall

kr-rank-schedule-status:
	$(PYTHON) tools/install_kr_rank_local_launchd.py status

print-staging-ops-schedule:
	@url="$$(deploy/azure/staging-url.sh)"; $(PYTHON) tools/install_ops_health_launchd.py render --url "$$url" --interval-minutes 30

install-staging-ops-schedule:
	@url="$$(deploy/azure/staging-url.sh)"; $(PYTHON) tools/install_ops_health_launchd.py install --url "$$url" --interval-minutes 30 --run-now

print-research-schedule:
	@$(PYTHON) tools/install_research_quality_launchd.py render

install-research-schedule:
	$(PYTHON) tools/install_research_quality_launchd.py install

uninstall-research-schedule:
	$(PYTHON) tools/install_research_quality_launchd.py uninstall

research-schedule-status:
	$(PYTHON) tools/install_research_quality_launchd.py status

configure-app-api:
	$(PYTHON) tools/configure_app_api.py

app-smoke:
	$(PYTHON) tools/smoke_app_api.py

user-flow:
	$(PYTHON) tools/smoke_user_flow.py

qa:
	$(PYTHON) tools/qa_phase2.py

api:
	$(PYTHON) -m uvicorn api.server:app --host 0.0.0.0 --port 8000 --reload

dag-dry-run:
	$(PYTHON) main_dag.py --dry-run --no-prefect

test:
	$(PYTHON) -m unittest test_contracts.py test_smallcap_scoring.py test_data_quality.py test_api_auth.py test_api_ops.py test_config.py test_pipeline_imports.py test_common_factor_scorer.py test_kr_pit_backtest.py test_kr_rank_health.py test_stock_quotes.py test_company_quality.py test_company_quality_report.py test_staging_status.py test_android_emulator_smoke.py test_storage_frames.py
