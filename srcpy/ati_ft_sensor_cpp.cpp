#include <pybind11/pybind11.h>
#include <pybind11/stl_bind.h>
#include <pybind11/eigen.h>

#include <AtiFTSensor.h>

namespace py = pybind11;
using namespace ati_ft_sensor;

PYBIND11_MODULE(ati_ft_sensor_cpp, m){
  py::class_<AtiFTSensor>(m, "AtiFTSensor")
    .def(py::init<>())
    .def("initialize",
         &AtiFTSensor::initialize,
         py::arg("sensor_ip") = "192.168.1.1",
         py::arg("sensor_port") = 49152,
         py::arg("local_port") = 49152)
    // .def("setBias", &AtiFTSensor::setBias)
    .def("resetBias", &AtiFTSensor::resetBias)
    .def("stop", &AtiFTSensor::stop)
    .def("getFT", &AtiFTSensor::getFT_vector)
    .def("stream", &AtiFTSensor::stream)
    ;
}