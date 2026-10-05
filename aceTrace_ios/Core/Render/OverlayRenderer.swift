import Foundation
import CoreGraphics
#if canImport(UIKit)
import UIKit
#endif

public enum OverlayRenderer {

    public static func render(
        frameIndex: Int,
        project: Project,
        context: CGContext,
        canvasSize: CGSize
    ) {
        for trajectory in project.trajectories {
            renderTrajectory(
                frameIndex: frameIndex,
                trajectory: trajectory,
                context: context,
                width: canvasSize.width,
                height: canvasSize.height
            )
        }

        if project.export.watermark {
            renderWatermark(context: context, width: canvasSize.width, height: canvasSize.height)
        }
    }

    private static func renderTrajectory(
        frameIndex: Int,
        trajectory: Trajectory,
        context: CGContext,
        width: CGFloat,
        height: CGFloat
    ) {
        let keypoints = trajectory.keypoints
        guard keypoints.count >= 2 else { return }

        let flightState = TimeMapping.computeFlightStateFromKeypoints(
            frameIndex: frameIndex,
            keypoints: keypoints
        )

        guard flightState.status != .beforeStart else { return }

        let path: TrajectoryPath = (trajectory.mode == "catmullRom")
            ? CatmullRomPath.fromPoints(keypoints.map { Point2D(x: $0.x, y: $0.y) })
            : BezierPath.fromKeypoints(keypoints)

        let style = trajectory.style
        let currentU = flightState.u

        let (uStart, uEnd): (Double, Double) = {
            switch style.trailMode {
            case "full":
                return (0.0, 1.0)
            case "comet":
                let tail = max(currentU - style.cometLengthFraction, 0.0)
                return (tail, currentU)
            default: // "tracer"
                return (0.0, currentU)
            }
        }()

        guard uEnd > uStart || currentU == 0.0 else { return }

        let numSamples = 60
        var points: [CGPoint] = []
        let step = (uEnd - uStart) / Double(max(numSamples, 1))
        for i in 0...numSamples {
            let u = min(max(uStart + Double(i) * step, 0.0), 1.0)
            let pt = path.point(u: u)
            points.append(CGPoint(x: pt.x * width, y: pt.y * height))
        }

        guard points.count >= 2 else { return }

        let cgPath = CGMutablePath()
        cgPath.move(to: points[0])
        for i in 1..<points.count {
            cgPath.addLine(to: points[i])
        }

        let baseWidth = CGFloat(max(style.lineWidth, 1.0))
        let glow = CGFloat(min(max(style.glow, 0.0), 1.0))

        context.saveGState()
        context.setLineCap(.round)
        context.setLineJoin(.round)

        // Outer glow
        context.saveGState()
        context.setLineWidth(baseWidth * 3.5)
        context.setShadow(offset: .zero, blur: baseWidth * 2.0, color: CGColor(srgbRed: 0, green: 0.9, blue: 1.0, alpha: 0.20 * glow))
        context.addPath(cgPath)
        context.strokePath()
        context.restoreGState()

        // Inner glow
        context.saveGState()
        context.setLineWidth(baseWidth * 1.8)
        context.setShadow(offset: .zero, blur: baseWidth * 0.8, color: CGColor(srgbRed: 0, green: 0.9, blue: 1.0, alpha: 0.45 * glow))
        context.addPath(cgPath)
        context.strokePath()
        context.restoreGState()

        // Core line
        context.saveGState()
        context.setLineWidth(baseWidth)
        context.setStrokeColor(CGColor(srgbRed: 0, green: 0.9, blue: 1.0, alpha: 1.0))
        context.addPath(cgPath)
        context.strokePath()
        context.restoreGState()

        context.restoreGState()

        // Distance HUD
        if trajectory.distance.visible, let lastPoint = points.last {
            renderDistanceHUD(
                progress: flightState.progress,
                total: trajectory.distance.value,
                unit: trajectory.distance.unit,
                easing: trajectory.distance.easing,
                headPoint: lastPoint,
                context: context
            )
        }
    }

    private static func renderDistanceHUD(
        progress: Double,
        total: Double,
        unit: String,
        easing: String,
        headPoint: CGPoint,
        context: CGContext
    ) {
        let currentDist = Int(round(TimeMapping.computeDistance(totalDistance: total, progress: progress, easing: easing)))
        let text = "\(currentDist) \(unit)"

        #if canImport(UIKit)
        let font = UIFont.boldSystemFont(ofSize: 14)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor.white
        ]
        let textSize = (text as NSString).size(withAttributes: attrs)
        let badgeRect = CGRect(
            x: headPoint.x - (textSize.width + 16) / 2.0,
            y: headPoint.y - 36,
            width: textSize.width + 16,
            height: textSize.height + 8
        )

        context.saveGState()
        let bgPath = UIBezierPath(roundedRect: badgeRect, cornerRadius: 6)
        context.setFillColor(UIColor(red: 0.07, green: 0.07, blue: 0.1, alpha: 0.8).cgColor)
        context.addPath(bgPath.cgPath)
        context.fillPath()

        context.setStrokeColor(UIColor(red: 0, green: 0.9, blue: 1.0, alpha: 0.4).cgColor)
        context.setLineWidth(1)
        context.addPath(bgPath.cgPath)
        context.strokePath()

        UIGraphicsPushContext(context)
        let textRect = CGRect(x: badgeRect.origin.x + 8, y: badgeRect.origin.y + 4, width: textSize.width, height: textSize.height)
        (text as NSString).draw(in: textRect, withAttributes: attrs)
        UIGraphicsPopContext()
        context.restoreGState()
        #endif
    }

    private static func renderWatermark(context: CGContext, width: CGFloat, height: CGFloat) {
        #if canImport(UIKit)
        let text = "Traced with AceTrace"
        let font = UIFont.boldSystemFont(ofSize: 12)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor(white: 1.0, alpha: 0.5)
        ]
        let textSize = (text as NSString).size(withAttributes: attrs)
        UIGraphicsPushContext(context)
        let rect = CGRect(x: width - textSize.width - 16, y: height - textSize.height - 16, width: textSize.width, height: textSize.height)
        (text as NSString).draw(in: rect, withAttributes: attrs)
        UIGraphicsPopContext()
        #endif
    }
}
