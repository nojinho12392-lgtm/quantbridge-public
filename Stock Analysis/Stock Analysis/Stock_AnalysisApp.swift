//
//  Stock_AnalysisApp.swift
//  Stock Analysis
//
//  Created by 노진호 on 3/10/26.
//

import SwiftUI

@main
struct Stock_AnalysisApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .scrollBounceBehavior(.always, axes: [.vertical, .horizontal])
        }
    }
}
