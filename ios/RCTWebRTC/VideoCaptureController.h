#if !TARGET_OS_TV

#import <Foundation/Foundation.h>
#import <WebRTC/RTCCameraVideoCapturer.h>

#import "CaptureController.h"

@interface VideoCaptureController : CaptureController
@property(nonatomic, readonly, strong) RTCCameraVideoCapturer *capturer;
@property(nonatomic, readonly, strong) AVCaptureDeviceFormat *selectedFormat;
@property(nonatomic, readonly, assign) int frameRate;
@property(nonatomic, assign) BOOL enableMultitaskingCameraAccess;

- (instancetype)initWithCapturer:(RTCCameraVideoCapturer *)capturer andConstraints:(NSDictionary *)constraints;
- (void)startCapture;
- (void)stopCapture;
- (void)switchCamera;
- (void)applyConstraints:(NSDictionary *)constraints error:(NSError **)outError;

/** Sets the zoom factor on the active capture device. Clamped to device range. */
- (void)setZoom:(CGFloat)zoomFactor;

/** Returns {"min": minZoom, "max": maxZoom} for the active capture device. */
- (NSDictionary *)getZoomRange;

/** Sets the exposure compensation bias (in EV stops). Returns nil on success or an error string. */
- (NSString *)setExposure:(float)bias;

/** Sets the white balance mode. Accepts "auto", "daylight", "cloudy", "fluorescent", "incandescent", "shade". */
- (NSString *)setWhiteBalance:(NSString *)mode;

/** Enables or disables video stabilization on the capture connection. */
- (NSString *)setStabilization:(BOOL)enabled;

/** Returns camera capabilities: exposureMin, exposureMax, wbModes, hasStabilization. */
- (NSDictionary *)getCameraCapabilities;

@end
#endif
