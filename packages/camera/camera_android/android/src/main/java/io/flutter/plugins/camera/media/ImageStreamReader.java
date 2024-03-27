// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camera.media;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugins.camera.types.CameraCaptureProperties;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.graphics.Rect;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;
import timber.log.Timber;

// Wraps an ImageReader to allow for testing of the image handler.
public class ImageStreamReader {

  /**
   * The image format we are going to send back to dart. Usually it's the same as streamImageFormat
   * but in the case of NV21 we will actually request YUV frames but convert it to NV21 before
   * sending to dart.
   */
  private final int dartImageFormat;

  private final ImageReader imageReader;
  private final ImageStreamReaderUtils imageStreamReaderUtils;

  private boolean isBusyInDetectingBarcodes = false;

  private BarcodeScanner barcodeScanner;

  /**
   * Creates a new instance of the {@link ImageStreamReader}.
   *
   * @param imageReader is the image reader that will receive frames
   * @param imageStreamReaderUtils is an instance of {@link ImageStreamReaderUtils}
   */
  @VisibleForTesting
  public ImageStreamReader(
      @NonNull ImageReader imageReader,
      int dartImageFormat,
      @NonNull ImageStreamReaderUtils imageStreamReaderUtils) {
    this.imageReader = imageReader;
    this.dartImageFormat = dartImageFormat;
    this.imageStreamReaderUtils = imageStreamReaderUtils;
  }

  /**
   * Creates a new instance of the {@link ImageStreamReader}.
   *
   * @param width is the image width
   * @param height is the image height
   * @param imageFormat is the {@link ImageFormat} that should be returned to dart.
   * @param maxImages is how many images can be acquired at one time, usually 1.
   */
  public ImageStreamReader(int width, int height, int imageFormat, int maxImages) {
    this.dartImageFormat = imageFormat;
    this.imageReader =
        ImageReader.newInstance(width, height, computeStreamImageFormat(imageFormat), maxImages);
    this.imageStreamReaderUtils = new ImageStreamReaderUtils();
  }

  /**
   * Returns the image format to stream based on a requested input format. Usually it's the same
   * except when dart is requesting NV21. In that case we stream YUV420 and process it into NV21
   * before sending the frames over.
   *
   * @param dartImageFormat is the image format dart is requesting.
   * @return the image format that should be streamed from the camera.
   */
  @VisibleForTesting
  public static int computeStreamImageFormat(int dartImageFormat) {
    if (dartImageFormat == ImageFormat.NV21) {
      return ImageFormat.YUV_420_888;
    } else {
      return dartImageFormat;
    }
  }

  /**
   * Processes a new frame (image) from the image reader and send the frame to Dart.
   *
   * @param image is the image which needs processed as an {@link Image}
   * @param captureProps is the capture props from the camera class as {@link
   *     CameraCaptureProperties}
   * @param imageStreamSink is the image stream sink from dart as a dart {@link
   *     EventChannel.EventSink}
   */
  @VisibleForTesting
  public void onImageAvailable(
      @NonNull Image image,
      @NonNull CameraCaptureProperties captureProps,
      @NonNull EventChannel.EventSink imageStreamSink) {
    try {
      Map<String, Object> imageBuffer = getImageBuffer(image, captureProps);
      final Handler handler = new Handler(Looper.getMainLooper());
      handler.post(() -> imageStreamSink.success(imageBuffer));
      image.close();

    } catch (IllegalStateException e) {
      // Handle "buffer is inaccessible" errors that can happen on some devices from ImageStreamReaderUtils.yuv420ThreePlanesToNV21()
      final Handler handler = new Handler(Looper.getMainLooper());
      handler.post(
          () ->
              imageStreamSink.error(
                  "IllegalStateException",
                  "Caught IllegalStateException: " + e.getMessage(),
                  null));
      image.close();
    }
  }

  private Map<String, Object> getImageBuffer(Image image, CameraCaptureProperties captureProps) {
    Map<String, Object> imageBuffer = new HashMap<>();
    // Get plane data ready
    if (dartImageFormat == ImageFormat.NV21) {
      imageBuffer.put("planes", parsePlanesForNv21(image));
    } else {
      imageBuffer.put("planes", parsePlanesForYuvOrJpeg(image));
    }
    imageBuffer.put("width", image.getWidth());
    imageBuffer.put("height", image.getHeight());
    imageBuffer.put("format", dartImageFormat);
    imageBuffer.put("lensAperture", captureProps.getLastLensAperture());
    imageBuffer.put("sensorExposureTime", captureProps.getLastSensorExposureTime());
    Integer sensorSensitivity = captureProps.getLastSensorSensitivity();
    imageBuffer.put("sensorSensitivity", sensorSensitivity == null ? null : (double) sensorSensitivity);
    return imageBuffer;
  }

  /**
   * Given an input image, will return a list of maps suitable to send back to dart where each map
   * describes the image plane.
   *
   * <p>For Yuv / Jpeg, we do no further processing on the frame so we simply send it as-is.
   *
   * @param image - the image to process.
   * @return parsed map describing the image planes to be sent to dart.
   */
  @NonNull
  public List<Map<String, Object>> parsePlanesForYuvOrJpeg(@NonNull Image image) {
    List<Map<String, Object>> planes = new ArrayList<>();

    // For YUV420 and JPEG, just send the data as-is for each plane.
    for (Image.Plane plane : image.getPlanes()) {
      ByteBuffer buffer = plane.getBuffer();

      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes, 0, bytes.length);

      Map<String, Object> planeBuffer = new HashMap<>();
      planeBuffer.put("bytesPerRow", plane.getRowStride());
      planeBuffer.put("bytesPerPixel", plane.getPixelStride());
      planeBuffer.put("bytes", bytes);

      planes.add(planeBuffer);
    }
    return planes;
  }

  /**
   * Given an input image, will return a single-plane NV21 image. Assumes YUV420 as an input type.
   *
   * @param image - the image to process.
   * @return parsed map describing the image planes to be sent to dart.
   */
  @NonNull
  public List<Map<String, Object>> parsePlanesForNv21(@NonNull Image image) {
    List<Map<String, Object>> planes = new ArrayList<>();

    // We will convert the YUV data to NV21 which is a single-plane image
    ByteBuffer bytes =
        imageStreamReaderUtils.yuv420ThreePlanesToNV21(
            image.getPlanes(), image.getWidth(), image.getHeight());

    Map<String, Object> planeBuffer = new HashMap<>();
    planeBuffer.put("bytesPerRow", image.getWidth());
    planeBuffer.put("bytesPerPixel", 1);
    planeBuffer.put("bytes", bytes.array());
    planes.add(planeBuffer);
    return planes;
  }

  /** Returns the image reader surface. */
  @NonNull
  public Surface getSurface() {
    return imageReader.getSurface();
  }

  /**
   * Subscribes the image stream reader to handle incoming images using onImageAvailable().
   *
   * @param captureProps is the capture props from the camera class as {@link
   *     CameraCaptureProperties}
   * @param imageStreamSink is the image stream sink from dart as {@link EventChannel.EventSink}
   * @param handler is generally the background handler of the camera as {@link Handler}
   */
  public void subscribeListener(
      @NonNull CameraCaptureProperties captureProps,
      @NonNull EventChannel.EventSink imageStreamSink,
      @NonNull Handler handler) {
    imageReader.setOnImageAvailableListener(
        reader -> {
          Image image = reader.acquireNextImage();
          if (image == null) return;

          onImageAvailable(image, captureProps, imageStreamSink);
        },
        handler);
  }

  public void subscribeBarcodeListener(
          @NonNull CameraCaptureProperties captureProps,
          final List<Integer> formatList,
          Integer imageRotation,
          @NonNull EventChannel.EventSink imageStreamSink,
          @NonNull Handler handler) {
    imageReader.setOnImageAvailableListener(
            reader -> {
              if (!isBusyInDetectingBarcodes) {
                Timber.d("Image Available");
                Image img = reader.acquireNextImage();
                // Use acquireNextImage since image reader is only for one image.
                if (img == null) {
                  Timber.d("No Image was found");
                  setBarcodeProcessingAsIdle();
                  return;
                }
                Timber.d("Image found..Will Send for Barcode Detection");
                handleDetectionForBarcode(captureProps, imageStreamSink, img, formatList, imageRotation);
              } else {
                Timber.d("Skipping..As Barcode is Still Processing");
              }
            },
            handler);
  }

  private void handleDetectionForBarcode(@NonNull CameraCaptureProperties captureProps,
                                         final EventChannel.EventSink imageStreamSink,
                                         Image image,
                                         List<Integer> formatList,
                                         Integer imageRotation) {

    try{
      setBarcodeProcessingAsBusy();
      InputImage inputImage = InputImage.fromMediaImage(image, imageRotation);
      Map<String, Object> imageBuffer = getImageBuffer(image, captureProps);
      if (formatList == null) {
        imageStreamSink.error("BarcodeDetectorError", "Invalid barcode formats", null);
        setBarcodeProcessingAsIdle();
        return;
      }

      BarcodeScannerOptions barcodeScannerOptions;
      if (formatList.size() > 1) {
        int[] array = new int[formatList.size()];
        for (int i = 1; i < formatList.size(); i++) {
          array[i] = formatList.get(i);
        }
        barcodeScannerOptions = new BarcodeScannerOptions.Builder().setBarcodeFormats(formatList.get(0), array).build();
      } else {
        barcodeScannerOptions = new BarcodeScannerOptions.Builder().setBarcodeFormats(formatList.get(0)).build();
      }

      Timber.d("Will process Image now");
      barcodeScanner = BarcodeScanning.getClient(barcodeScannerOptions);
      barcodeScanner.process(inputImage).addOnSuccessListener(new OnSuccessListener<List<Barcode>>() {
        @Override
        public void onSuccess(List<Barcode> barcodes) {
          Timber.d("Barcode Detection has ended Successfully");
          List<Map<String, Object>> barcodeList = new ArrayList<>(barcodes.size());
          for (Barcode barcode : barcodes) {
            Map<String, Object> barcodeMap = getBarcodeMap(barcode);
            barcodeList.add(barcodeMap);
          }
          Timber.d("Total Number of Barcodes found:"+barcodeList.size());
          final Handler handler = new Handler(Looper.getMainLooper());
          Timber.d("Will reply Dart with received barcode");
          Map<String, Object> imageBarcodeData = new HashMap<>();
          imageBarcodeData.put("image",imageBuffer);
          imageBarcodeData.put("barcodes",barcodeList);
          handler.post(() -> imageStreamSink.success(imageBarcodeData));
        }
      }).addOnFailureListener(new OnFailureListener() {
        @Override
        public void onFailure(@NonNull Exception e) {
          Timber.d("Barcode Detection has failed.Error: "+e.toString());
          final Handler handler = new Handler(Looper.getMainLooper());
          handler.post(() -> imageStreamSink.error("BarcodeDetectorError", e.toString(), null));
        }
      }).addOnCompleteListener((Task<List<Barcode>> firebaseVisionBarcodes) -> {
        // regardless of failure or success, close the previous frame
        // and process the next one.
        setBarcodeProcessingAsIdle();
        image.close();
      });
    } catch (Exception e){
      Timber.d("Exception occurred");
    }
  }

  @NonNull
  private Map<String, Object> getBarcodeMap(Barcode barcode) {
    Map<String, Object> barcodeMap = new HashMap<>();
    int valueType = barcode.getValueType();
    barcodeMap.put("type", valueType);
    barcodeMap.put("format", barcode.getFormat());
    barcodeMap.put("rawValue", barcode.getRawValue());
    barcodeMap.put("rawBytes", barcode.getRawBytes());
    barcodeMap.put("displayValue", barcode.getDisplayValue());
    Rect bb = barcode.getBoundingBox();
    if (bb != null) {
      barcodeMap.put("boundingBoxBottom", bb.bottom);
      barcodeMap.put("boundingBoxLeft", bb.left);
      barcodeMap.put("boundingBoxRight", bb.right);
      barcodeMap.put("boundingBoxTop", bb.top);
    }
    switch (valueType) {
      case Barcode.TYPE_UNKNOWN:
      case Barcode.TYPE_ISBN:
      case Barcode.TYPE_PRODUCT:
      case Barcode.TYPE_TEXT:
        break;
      case Barcode.TYPE_WIFI:
        barcodeMap.put("ssid", barcode.getWifi().getSsid());
        barcodeMap.put("password", barcode.getWifi().getPassword());
        barcodeMap.put("encryption", barcode.getWifi().getEncryptionType());
        break;
      case Barcode.TYPE_URL:
        barcodeMap.put("title", barcode.getUrl().getTitle());
        barcodeMap.put("url", barcode.getUrl().getUrl());
        break;
      case Barcode.TYPE_EMAIL:
        barcodeMap.put("address", barcode.getEmail().getAddress());
        barcodeMap.put("body", barcode.getEmail().getBody());
        barcodeMap.put("subject", barcode.getEmail().getSubject());
        barcodeMap.put("emailType", barcode.getEmail().getType());
        break;

      case Barcode.TYPE_PHONE:
        barcodeMap.put("number", barcode.getPhone().getNumber());
        barcodeMap.put("phoneType", barcode.getPhone().getType());
        break;
      case Barcode.TYPE_SMS:
        barcodeMap.put("message", barcode.getSms().getMessage());
        barcodeMap.put("number", barcode.getSms().getPhoneNumber());
        break;
      case Barcode.TYPE_GEO:
        barcodeMap.put("latitude", barcode.getGeoPoint().getLat());
        barcodeMap.put("longitude", barcode.getGeoPoint().getLng());
        break;
      case Barcode.TYPE_DRIVER_LICENSE:
        barcodeMap.put("addressCity", barcode.getDriverLicense().getAddressCity());
        barcodeMap.put("addressState", barcode.getDriverLicense().getAddressState());
        barcodeMap.put("addressZip", barcode.getDriverLicense().getAddressZip());
        barcodeMap.put("addressStreet", barcode.getDriverLicense().getAddressStreet());
        barcodeMap.put("issueDate", barcode.getDriverLicense().getIssueDate());
        barcodeMap.put("birthDate", barcode.getDriverLicense().getBirthDate());
        barcodeMap.put("expiryDate", barcode.getDriverLicense().getExpiryDate());
        barcodeMap.put("gender", barcode.getDriverLicense().getGender());
        barcodeMap.put("licenseNumber", barcode.getDriverLicense().getLicenseNumber());
        barcodeMap.put("firstName", barcode.getDriverLicense().getFirstName());
        barcodeMap.put("lastName", barcode.getDriverLicense().getLastName());
        barcodeMap.put("country", barcode.getDriverLicense().getIssuingCountry());
        break;

      case Barcode.TYPE_CONTACT_INFO:
        barcodeMap.put("firstName", barcode.getContactInfo().getName().getFirst());
        barcodeMap.put("lastName", barcode.getContactInfo().getName().getLast());
        barcodeMap.put("formattedName", barcode.getContactInfo().getName().getFormattedName());
        barcodeMap.put("organization", barcode.getContactInfo().getOrganization());
        List<Map<String, Object>> queries = new ArrayList<>();
        for (Barcode.Address address : barcode.getContactInfo().getAddresses()) {
          Map<String, Object> addressMap = new HashMap<>();
          addressMap.put("addressType", address.getType());
          List<String> addressLines = new ArrayList<>();
          for (String addressLine : address.getAddressLines()) {
            addressLines.add(addressLine);
          }
          addressMap.put("addressLines", addressLines);
          queries.add(addressMap);
        }
        barcodeMap.put("addresses", queries);
        queries = new ArrayList<>();
        for (Barcode.Phone phone : barcode.getContactInfo().getPhones()) {
          Map<String, Object> phoneMap = new HashMap<>();
          phoneMap.put("number", phone.getNumber());
          phoneMap.put("phoneType", phone.getType());
          queries.add(phoneMap);
        }
        barcodeMap.put("phones", queries);
        queries = new ArrayList<>();
        for (Barcode.Email email : barcode.getContactInfo().getEmails()) {
          Map<String, Object> emailMap = new HashMap<>();
          emailMap.put("address", email.getAddress());
          emailMap.put("body", email.getBody());
          emailMap.put("subject", email.getSubject());
          emailMap.put("emailType", email.getType());
          queries.add(emailMap);
        }
        barcodeMap.put("emails", queries);
        List<String> urls = new ArrayList<>(barcode.getContactInfo().getUrls());
        barcodeMap.put("urls", urls);
        break;

      case Barcode.TYPE_CALENDAR_EVENT:
        barcodeMap.put("description", barcode.getCalendarEvent().getDescription());
        barcodeMap.put("location", barcode.getCalendarEvent().getLocation());
        barcodeMap.put("status", barcode.getCalendarEvent().getStatus());
        barcodeMap.put("summary", barcode.getCalendarEvent().getSummary());
        barcodeMap.put("organizer", barcode.getCalendarEvent().getOrganizer());
        barcodeMap.put("start", barcode.getCalendarEvent().getStart().getRawValue());
        barcodeMap.put("end", barcode.getCalendarEvent().getEnd().getRawValue());
        break;
    }
    return barcodeMap;
  }

  @NonNull
  private byte[] getBytes(ByteBuffer bytesBuffer) {
    bytesBuffer.rewind();
    int lengthOfBuffer = bytesBuffer.limit();
    byte[] imageBytes = new byte[lengthOfBuffer];
    bytesBuffer.get(imageBytes, 0, lengthOfBuffer);
    return imageBytes;
  }

  @NonNull
  private ByteBuffer appendByteBuffer(ByteBuffer bytesBuffer, ByteBuffer buffer) {
    int previousLengthOfBuffer = bytesBuffer.limit();
    int newLengthOfBuffer = previousLengthOfBuffer + buffer.limit();
    bytesBuffer.rewind();
    bytesBuffer = ByteBuffer.allocate(newLengthOfBuffer).put(bytesBuffer).put(buffer);
    return bytesBuffer;
  }

  private void setBarcodeProcessingAsBusy() {
    isBusyInDetectingBarcodes = true;
  }

  private void setBarcodeProcessingAsIdle() {
    isBusyInDetectingBarcodes = false;
  }

  public void closeDetector() {
    if(barcodeScanner != null){
      barcodeScanner.close();
    }
  }

  /**
   * Removes the listener from the image reader.
   *
   * @param handler is generally the background handler of the camera
   */
  public void removeListener(@NonNull Handler handler) {
    imageReader.setOnImageAvailableListener(null, handler);
  }

  /** Closes the image reader. */
  public void close() {
    imageReader.close();
  }
}
