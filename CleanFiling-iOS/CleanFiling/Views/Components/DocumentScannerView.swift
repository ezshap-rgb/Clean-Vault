import PDFKit
import SwiftUI
import VisionKit

struct DocumentScannerView: UIViewControllerRepresentable {
    let scanAsPdf: Bool
    let onComplete: (URL, String) -> Void
    let onCancel: () -> Void
    let onError: (String) -> Void

    static var isSupported: Bool {
        VNDocumentCameraViewController.isSupported
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: VNDocumentCameraViewController, context: Context) {}

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        private let parent: DocumentScannerView

        init(_ parent: DocumentScannerView) {
            self.parent = parent
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            parent.onCancel()
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
            parent.onError(error.localizedDescription)
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            guard scan.pageCount > 0 else {
                parent.onCancel()
                return
            }

            let tempDir = FileManager.default.temporaryDirectory
            if parent.scanAsPdf {
                let pdf = PDFDocument()
                for pageIndex in 0..<scan.pageCount {
                    let image = scan.imageOfPage(at: pageIndex)
                    if let pdfPage = PDFPage(image: image) {
                        pdf.insert(pdfPage, at: pdf.pageCount)
                    }
                }
                let outURL = tempDir.appendingPathComponent("Scan_\(Int(Date().timeIntervalSince1970)).pdf")
                if pdf.write(to: outURL) {
                    parent.onComplete(outURL, "application/pdf")
                } else {
                    parent.onError("Failed to generate PDF from scan.")
                }
            } else {
                let firstImage = scan.imageOfPage(at: 0)
                let outURL = tempDir.appendingPathComponent("Scan_\(Int(Date().timeIntervalSince1970)).jpg")
                if let jpeg = firstImage.jpegData(compressionQuality: 0.88) {
                    do {
                        try jpeg.write(to: outURL, options: .atomic)
                        parent.onComplete(outURL, "image/jpeg")
                    } catch {
                        parent.onError(error.localizedDescription)
                    }
                } else {
                    parent.onError("Failed to encode scanned JPEG.")
                }
            }
        }
    }
}
