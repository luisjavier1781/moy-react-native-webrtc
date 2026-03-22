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

@end
#endif
