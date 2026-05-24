import SwiftUI

enum LucideIcon: String {
    case activity = "lucide_activity"
    case airVent = "lucide_air_vent"
    case anchor = "lucide_anchor"
    case apple = "lucide_apple"
    case arrowUpDown = "lucide_arrow_up_down"
    case audioWaveform = "lucide_audio_waveform"
    case badgeDollarSign = "lucide_badge_dollar_sign"
    case banknote = "lucide_banknote"
    case barChart3 = "lucide_bar_chart_3"
    case battery = "lucide_battery"
    case bell = "lucide_bell"
    case beaker = "lucide_beaker"
    case bot = "lucide_bot"
    case briefcaseBusiness = "lucide_briefcase_business"
    case building = "lucide_building"
    case building2 = "lucide_building_2"
    case cable = "lucide_cable"
    case calendarCheck = "lucide_calendar_check"
    case calendarClock = "lucide_calendar_clock"
    case car = "lucide_car"
    case chartCandlestick = "lucide_chart_candlestick"
    case check = "lucide_check"
    case circleArrowDown = "lucide_circle_arrow_down"
    case circleArrowUp = "lucide_circle_arrow_up"
    case circuitBoard = "lucide_circuit_board"
    case clapperboard = "lucide_clapperboard"
    case cloud = "lucide_cloud"
    case cpu = "lucide_cpu"
    case creditCard = "lucide_credit_card"
    case database = "lucide_database"
    case dna = "lucide_dna"
    case edit = "lucide_edit"
    case eye = "lucide_eye"
    case factory = "lucide_factory"
    case flaskConical = "lucide_flask_conical"
    case fuel = "lucide_fuel"
    case gamepad2 = "lucide_gamepad_2"
    case gem = "lucide_gem"
    case gitCompare = "lucide_git_compare"
    case globe2 = "lucide_globe_2"
    case hammer = "lucide_hammer"
    case hardDrive = "lucide_hard_drive"
    case heart = "lucide_heart"
    case heartPulse = "lucide_heart_pulse"
    case hospital = "lucide_hospital"
    case hotel = "lucide_hotel"
    case landmark = "lucide_landmark"
    case layoutDashboard = "lucide_layout_dashboard"
    case leaf = "lucide_leaf"
    case lightbulb = "lucide_lightbulb"
    case lineChart = "lucide_line_chart"
    case listOrdered = "lucide_list_ordered"
    case microchip = "lucide_microchip"
    case monitorCog = "lucide_monitor_cog"
    case network = "lucide_network"
    case newspaper = "lucide_newspaper"
    case palette = "lucide_palette"
    case pickaxe = "lucide_pickaxe"
    case pieChart = "lucide_pie_chart"
    case pill = "lucide_pill"
    case plane = "lucide_plane"
    case plus = "lucide_plus"
    case radio = "lucide_radio"
    case radioTower = "lucide_radio_tower"
    case refreshCw = "lucide_refresh_cw"
    case rocket = "lucide_rocket"
    case search = "lucide_search"
    case server = "lucide_server"
    case ship = "lucide_ship"
    case shieldCheck = "lucide_shield_check"
    case shoppingBag = "lucide_shopping_bag"
    case shoppingCart = "lucide_shopping_cart"
    case slidersHorizontal = "lucide_sliders_horizontal"
    case sparkles = "lucide_sparkles"
    case square = "lucide_square"
    case star = "lucide_star"
    case stethoscope = "lucide_stethoscope"
    case store = "lucide_store"
    case syringe = "lucide_syringe"
    case target = "lucide_target"
    case trendingDown = "lucide_trending_down"
    case trendingUp = "lucide_trending_up"
    case triangleAlert = "lucide_triangle_alert"
    case truck = "lucide_truck"
    case utensils = "lucide_utensils"
    case userRound = "lucide_user_round"
    case warehouse = "lucide_warehouse"
    case workflow = "lucide_workflow"
    case x = "lucide_x"
    case zap = "lucide_zap"
}

struct LucideIconView: View {
    let icon: LucideIcon
    var size: CGFloat = 20

    var body: some View {
        Image(icon.rawValue)
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

func lucideIcon(forSystemSymbol symbol: String) -> LucideIcon {
    switch symbol {
    case "calendar", "calendar.badge.clock", "clock", "clock.badge.checkmark":
        return .calendarClock
    case "newspaper":
        return .newspaper
    case "chart.line.uptrend.xyaxis", "chart.line.uptrend.xyaxis.circle.fill", "chart.xyaxis.line", "chart.bar.xaxis", "waveform.path.ecg":
        return .activity
    case "chart.pie", "chart.pie.fill":
        return .pieChart
    case "chart.candlestick":
        return .chartCandlestick
    case "scope":
        return .target
    case "exclamationmark.triangle", "exclamationmark.triangle.fill":
        return .triangleAlert
    case "checkmark", "checkmark.seal", "checkmark.seal.fill", "checkmark.circle.fill", "checkmark.icloud", "checklist", "checklist.checked":
        return .shieldCheck
    case "bolt.fill", "bolt.circle.fill", "bolt.horizontal":
        return .zap
    case "eye":
        return .eye
    case "heart", "heart.fill", "heart.circle.fill", "star", "star.fill", "star.circle.fill":
        return .heart
    case "bell", "bell.badge.fill":
        return .bell
    case "magnifyingglass", "doc.text.magnifyingglass":
        return .search
    case "arrow.clockwise", "arrow.triangle.2.circlepath":
        return .refreshCw
    case "slider.horizontal.3", "line.3.horizontal.decrease.circle", "speedometer":
        return .slidersHorizontal
    case "rectangle.split.2x1", "square.split.2x1", "checkmark.rectangle.split.2x1", "arrow.left.arrow.right":
        return .gitCompare
    case "xmark", "xmark.circle.fill":
        return .x
    case "plus", "plus.circle", "plus.forwardslash.minus":
        return .plus
    case "arrow.up.arrow.down", "arrow.up.down", "scale.3d":
        return .arrowUpDown
    case "building.2":
        return .building2
    case "square.grid.2x2":
        return .layoutDashboard
    case "brain.head.profile", "sparkles":
        return .sparkles
    case "antenna.radiowaves.left.and.right", "antenna.radiowaves.left.and.right.circle.fill":
        return .audioWaveform
    case "banknote":
        return .landmark
    case "icloud.slash", "stethoscope":
        return .shieldCheck
    case "diamond.fill", "tag.circle.fill":
        return .target
    case "percent":
        return .activity
    case "questionmark.circle", "info.circle":
        return .lightbulb
    default:
        return .activity
    }
}
