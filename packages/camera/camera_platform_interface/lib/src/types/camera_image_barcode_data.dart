import 'barcode_scanner.dart';
import 'camera_image_data.dart';

class CameraImageBarcodeData {

  CameraImageBarcodeData({
    required this.cameraImageData,
    required this.barcodes,
  });

  final CameraImageData cameraImageData;
  final List<Barcode> barcodes;
}
