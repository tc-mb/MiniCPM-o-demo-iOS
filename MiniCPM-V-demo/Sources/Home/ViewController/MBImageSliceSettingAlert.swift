//
//  MBImageSliceSettingAlert.swift
//  MiniCPM-V-demo
//
//  Lightweight UIAlertController-style picker for the per-image slice
//  cap (1..9) used by MiniCPM-V's llava-uhd style preprocessing.
//
//  Design notes:
//   * UIAlertController doesn't expose an arbitrary content view via a
//     supported API, so we wrap the slider, value label and hint inside
//     the alert's `message` slot using a custom container view.  This is
//     the same trick used by many open-source iOS pickers and is stable
//     across iOS 13–17.
//   * Live drag of the slider only updates the in-dialog preview label.
//     We persist + push to native only on "完成"; "取消" is a no-op.
//   * Mirrors the Android `dialog_image_slice.xml` and HarmonyOS
//     `ImageSliceDialog.ets` wording to keep the three demos consistent.
//

import Foundation
import UIKit

@MainActor
enum MBImageSliceSettingAlert {

    /// Pop the slice-cap picker from `presenter`.  `onConfirm` is
    /// invoked exactly once on "完成" with the chosen value (clamped).
    static func present(from presenter: UIViewController,
                        initialValue: Int,
                        onConfirm: @escaping @MainActor (Int) -> Void) {
        let clamped = max(ImageSliceSetting.minSlice,
                          min(ImageSliceSetting.maxSlice, initialValue))

        // We need extra newlines in the message so UIAlertController
        // reserves vertical space for our overlay subviews (slider +
        // value label + hint).  Each "\n" reserves roughly one font
        // line; tune until the pinned subviews fit on common phones.
        // Reserve nine '\n' lines of vertical space inside the alert
        // message so the slider/value/hint subviews fit. We keep this
        // padding outside the i18n string itself to avoid translators
        // accidentally compressing the leading whitespace.
        let messageBody = L.ImageSlice.message.loc
        let alert = UIAlertController(
            title: L.ImageSlice.title.loc,
            message: "\n\n\n\n\n\n\n\n\n" + messageBody,
            preferredStyle: .alert
        )

        // Big value preview.  Anchored to the top of the alert content
        // area so it sits right under the title.
        let valueLabel = UILabel()
        valueLabel.text = "\(clamped)"
        valueLabel.font = UIFont.systemFont(ofSize: 36, weight: .bold)
        valueLabel.textColor = alert.view.tintColor ?? .systemBlue
        valueLabel.textAlignment = .center
        valueLabel.translatesAutoresizingMaskIntoConstraints = false
        alert.view.addSubview(valueLabel)

        // Slider 1..9, step 1.  We snap on change and also on
        // touch-up to guarantee an integer is delivered.
        let slider = UISlider()
        slider.minimumValue = Float(ImageSliceSetting.minSlice)
        slider.maximumValue = Float(ImageSliceSetting.maxSlice)
        slider.value = Float(clamped)
        slider.isContinuous = true
        slider.translatesAutoresizingMaskIntoConstraints = false
        alert.view.addSubview(slider)

        // Min / max captions under the slider.  Plain UILabels because
        // adding 9 ticks via a custom drawing layer is overkill for a
        // settings popup.
        let minLabel = UILabel()
        minLabel.text = L.ImageSlice.labelMin.loc
        minLabel.font = UIFont.systemFont(ofSize: 11)
        minLabel.textColor = .secondaryLabel
        minLabel.translatesAutoresizingMaskIntoConstraints = false
        alert.view.addSubview(minLabel)

        let maxLabel = UILabel()
        maxLabel.text = L.ImageSlice.labelMax.loc
        maxLabel.font = UIFont.systemFont(ofSize: 11)
        maxLabel.textColor = .secondaryLabel
        maxLabel.textAlignment = .right
        maxLabel.translatesAutoresizingMaskIntoConstraints = false
        alert.view.addSubview(maxLabel)

        NSLayoutConstraint.activate([
            valueLabel.topAnchor.constraint(equalTo: alert.view.topAnchor, constant: 60),
            valueLabel.centerXAnchor.constraint(equalTo: alert.view.centerXAnchor),

            slider.topAnchor.constraint(equalTo: valueLabel.bottomAnchor, constant: 8),
            slider.leadingAnchor.constraint(equalTo: alert.view.leadingAnchor, constant: 24),
            slider.trailingAnchor.constraint(equalTo: alert.view.trailingAnchor, constant: -24),

            minLabel.topAnchor.constraint(equalTo: slider.bottomAnchor, constant: 2),
            minLabel.leadingAnchor.constraint(equalTo: slider.leadingAnchor, constant: 4),

            maxLabel.topAnchor.constraint(equalTo: slider.bottomAnchor, constant: 2),
            maxLabel.trailingAnchor.constraint(equalTo: slider.trailingAnchor, constant: -4),
        ])

        // Capture-list note: the closure retains `valueLabel` only; alert
        // controller keeps the slider alive via subview ownership.
        let snap = MBImageSliceSliderProxy(label: valueLabel)
        slider.addTarget(snap, action: #selector(MBImageSliceSliderProxy.sliderChanged(_:)), for: .valueChanged)
        objc_setAssociatedObject(slider, &MBImageSliceSliderProxy.assocKey, snap, .OBJC_ASSOCIATION_RETAIN)

        alert.addAction(UIAlertAction(title: L.Common.cancel.loc, style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: L.Common.done.loc, style: .default) { _ in
            let chosen = Int(round(slider.value))
            onConfirm(chosen)
        })

        presenter.present(alert, animated: true)
    }
}

// Tiny ObjC-accessible proxy so the slider's @objc target/action machinery
// can keep a strong reference without us subclassing UIAlertController.
@MainActor
final class MBImageSliceSliderProxy: NSObject {
    static var assocKey: UInt8 = 0
    weak var label: UILabel?
    init(label: UILabel) { self.label = label }
    @objc func sliderChanged(_ sender: UISlider) {
        let snapped = Int(round(sender.value))
        if Float(snapped) != sender.value {
            sender.value = Float(snapped)
        }
        label?.text = "\(snapped)"
    }
}
