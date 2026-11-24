import QuickLook
import SwiftUI

struct QuickLookItem: Identifiable {
    let id = UUID()
    let url: URL
    let title: String
}

struct QuickLookPreview: UIViewControllerRepresentable {
    let item: QuickLookItem

    func makeCoordinator() -> Coordinator {
        Coordinator(item: item)
    }

    func makeUIViewController(context: Context) -> UINavigationController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        let nav = UINavigationController(rootViewController: controller)
        controller.navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .done,
            target: context.coordinator,
            action: #selector(Coordinator.dismissTapped)
        )
        context.coordinator.controller = controller
        return nav
    }

    func updateUIViewController(_ uiViewController: UINavigationController, context: Context) {}

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let item: QuickLookItem
        weak var controller: QLPreviewController?

        init(item: QuickLookItem) {
            self.item = item
        }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
            1
        }

        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            PreviewItem(url: item.url, title: item.title)
        }

        @objc func dismissTapped() {
            controller?.dismiss(animated: true)
        }
    }

    private final class PreviewItem: NSObject, QLPreviewItem {
        let previewItemURL: URL?
        let previewItemTitle: String?

        init(url: URL, title: String) {
            self.previewItemURL = url
            self.previewItemTitle = title
        }
    }
}
