# Demo to get ati sensor readings in python

import os

from ati_ft_sensor_cpp import AtiFTSensor

sensor = AtiFTSensor()

sensor_ip = os.getenv("ATI_NETFT_IP", "192.168.1.1")
sensor_port = int(os.getenv("ATI_NETFT_PORT", "49152"))
local_port = int(os.getenv("ATI_LOCAL_PORT", "49152"))

sensor.initialize(sensor_ip=sensor_ip, sensor_port=sensor_port, local_port=local_port)

while True:
    ft = sensor.getFT()
    print(ft)