import cv2
import os
from picamera2 import Picamera2

name = 'Rebeca'

dataset_path = os.path.join("dataset", name)
os.makedirs(dataset_path, exist_ok=True)

cam = Picamera2()
config = cam.create_preview_configuration(
    main={"size": (512, 304), "format": "BGR888"}
)
cam.configure(config)
cam.start()

img_counter = 0

while True:
    frame = cam.capture_array()
    frame = cv2.cvtColor(frame, cv2.COLOR_RGB2BGR)

    cv2.imshow("Press Space to take a photo", frame)

    k = cv2.waitKey(1)

    if k % 256 == 27:
        break
    elif k % 256 == 32:
        img_name = "dataset/" + name + "/image_{}.jpg".format(img_counter)
        cv2.imwrite(img_name, frame)
        print("{} written!".format(img_name))
        img_counter += 1

cv2.destroyAllWindows()
cam.stop()
