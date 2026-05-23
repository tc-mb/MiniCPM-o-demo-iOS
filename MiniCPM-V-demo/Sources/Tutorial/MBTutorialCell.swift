//
//  MBTutorialCell.swift
//  MiniCPM-V-demo
//
//  教程卡片 Cell：图标 + 标题 + 正文 + 截图占位
//

import Foundation
import UIKit
import SnapKit

class MBTutorialCell: UITableViewCell {

    static let reuseId = "MBTutorialCell"

    private let card: UIView = {
        let v = UIView()
        v.backgroundColor = .white
        v.layer.cornerRadius = 16
        v.layer.shadowColor = UIColor.black.cgColor
        v.layer.shadowOpacity = 0.06
        v.layer.shadowRadius = 8
        v.layer.shadowOffset = CGSize(width: 0, height: 2)
        return v
    }()

    private let symbolBg: UIView = {
        let v = UIView()
        v.layer.cornerRadius = 18
        return v
    }()

    private let symbolView: UIImageView = {
        let iv = UIImageView()
        iv.contentMode = .scaleAspectFit
        iv.tintColor = .white
        iv.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 18, weight: .semibold)
        return iv
    }()

    private let indexLabel: UILabel = {
        let l = UILabel()
        l.font = .systemFont(ofSize: 13, weight: .semibold)
        l.textColor = UIColor.mb_color(with: "#8E8E93") ?? .secondaryLabel
        return l
    }()

    private let titleLabel: UILabel = {
        let l = UILabel()
        l.font = .systemFont(ofSize: 18, weight: .semibold)
        l.textColor = .black
        l.numberOfLines = 0
        return l
    }()

    private let bodyLabel: UILabel = {
        let l = UILabel()
        l.font = .systemFont(ofSize: 15)
        l.textColor = UIColor.mb_color(with: "#3C3C43") ?? .darkGray
        l.numberOfLines = 0
        return l
    }()

    private let screenshotView: UIImageView = {
        let iv = UIImageView()
        // 截图为竖屏手机截屏，必须用 aspectFit 完整显示，否则会被裁成中间一条像素
        iv.contentMode = .scaleAspectFit
        iv.clipsToBounds = true
        iv.layer.cornerRadius = 10
        iv.backgroundColor = UIColor.mb_color(with: "#F2F2F7") ?? .systemGray6
        return iv
    }()

    private let placeholderLabel: UILabel = {
        let l = UILabel()
        l.font = .systemFont(ofSize: 13)
        l.textColor = UIColor.mb_color(with: "#8E8E93") ?? .secondaryLabel
        l.textAlignment = .center
        l.numberOfLines = 2
        return l
    }()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        contentView.backgroundColor = .clear
        selectionStyle = .none
        setupSubviews()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    private func setupSubviews() {
        contentView.addSubview(card)
        card.addSubview(symbolBg)
        symbolBg.addSubview(symbolView)
        card.addSubview(indexLabel)
        card.addSubview(titleLabel)
        card.addSubview(bodyLabel)
        card.addSubview(screenshotView)
        screenshotView.addSubview(placeholderLabel)

        card.snp.makeConstraints { make in
            make.top.equalToSuperview().offset(8)
            make.bottom.equalToSuperview().offset(-8)
            make.left.equalToSuperview().offset(16)
            make.right.equalToSuperview().offset(-16)
        }

        symbolBg.snp.makeConstraints { make in
            make.top.equalToSuperview().offset(20)
            make.left.equalToSuperview().offset(20)
            make.size.equalTo(36)
        }

        symbolView.snp.makeConstraints { make in
            make.center.equalToSuperview()
            make.size.equalTo(20)
        }

        indexLabel.snp.makeConstraints { make in
            make.centerY.equalTo(symbolBg)
            make.left.equalTo(symbolBg.snp.right).offset(12)
        }

        titleLabel.snp.makeConstraints { make in
            make.top.equalTo(symbolBg.snp.bottom).offset(14)
            make.left.equalToSuperview().offset(20)
            make.right.equalToSuperview().offset(-20)
        }

        bodyLabel.snp.makeConstraints { make in
            make.top.equalTo(titleLabel.snp.bottom).offset(8)
            make.left.equalToSuperview().offset(20)
            make.right.equalToSuperview().offset(-20)
        }

        screenshotView.snp.makeConstraints { make in
            make.top.equalTo(bodyLabel.snp.bottom).offset(14)
            make.left.equalToSuperview().offset(20)
            make.right.equalToSuperview().offset(-20)
            // 占位/默认高度，configure 里会按真实图片比例 remake
            make.height.equalTo(160)
            make.bottom.equalToSuperview().offset(-20)
        }

        placeholderLabel.snp.makeConstraints { make in
            make.center.equalToSuperview()
            make.left.equalToSuperview().offset(12)
            make.right.equalToSuperview().offset(-12)
        }
    }

    func configure(_ step: MBTutorialStep) {
        symbolBg.backgroundColor = step.symbolBgColor
        symbolView.image = UIImage(systemName: step.symbolName)
        indexLabel.text = String(format: L.Tutorial.stepLabelFormat.loc, step.index)
        titleLabel.text = step.title

        let para = NSMutableParagraphStyle()
        para.lineSpacing = 4
        let attr = NSAttributedString(
            string: step.body,
            attributes: [
                .paragraphStyle: para,
                .font: UIFont.systemFont(ofSize: 15),
                .foregroundColor: UIColor.mb_color(with: "#3C3C43") ?? .darkGray
            ]
        )
        bodyLabel.attributedText = attr

        if let asset = step.screenshotAsset, let img = UIImage(named: asset) {
            screenshotView.image = img
            placeholderLabel.isHidden = true
            screenshotView.contentMode = .scaleAspectFit

            // 按图片真实宽高比 remake 高度约束。
            // 限制上限：竖屏手机截图比例 ~2.16，会让 cell 太长；最大不超过 width * 1.6 (~520pt)。
            let aspect = img.size.width > 0 ? img.size.height / img.size.width : 1.0
            let cappedAspect = min(aspect, 1.6)
            screenshotView.snp.remakeConstraints { make in
                make.top.equalTo(bodyLabel.snp.bottom).offset(14)
                make.left.equalToSuperview().offset(20)
                make.right.equalToSuperview().offset(-20)
                make.height.equalTo(screenshotView.snp.width).multipliedBy(cappedAspect)
                make.bottom.equalToSuperview().offset(-20)
            }
        } else {
            screenshotView.image = nil
            placeholderLabel.text = String(format: L.Tutorial.placeholderFormat.loc, step.placeholderHint)
            placeholderLabel.isHidden = false
            screenshotView.snp.remakeConstraints { make in
                make.top.equalTo(bodyLabel.snp.bottom).offset(14)
                make.left.equalToSuperview().offset(20)
                make.right.equalToSuperview().offset(-20)
                make.height.equalTo(160)
                make.bottom.equalToSuperview().offset(-20)
            }
        }
    }
}
