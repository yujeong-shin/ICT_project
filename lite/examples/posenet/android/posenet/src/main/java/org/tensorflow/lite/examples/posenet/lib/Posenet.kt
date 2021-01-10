/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tensorflow.lite.examples.posenet.lib


import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

enum class BodyPart {
    NOSE,
    LEFT_EYE,
    RIGHT_EYE,
    LEFT_EAR,
    RIGHT_EAR,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_WRIST,
    RIGHT_WRIST,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE
}

var frameCounter = 0

// 12가지 각도 체크
var LEFT_ForeArm: Int = 0
var LEFT_Arm: Int = 0
var LEFT_Body: Int = 0
var LEFT_KneeUp: Int = 0
var LEFT_KneeDown: Int = 0
var RIGHT_ForeArm: Int = 0
var RIGHT_Arm: Int = 0
var RIGHT_Body: Int = 0
var RIGHT_KneeUp: Int = 0
var RIGHT_KneeDown: Int = 0
var Center_Body: Int = 0
var Center_Shoulder: Int = 0


class Position {
    var x: Int = 0
    var y: Int = 0
}

class KeyPoint {
    var bodyPart: BodyPart = BodyPart.NOSE
    var position: Position = Position()
    var score: Float = 0.0f
}


class Person {
    var keyPoints = listOf<KeyPoint>()
    var score: Float = 0.0f
}

enum class Device {
    CPU,
    NNAPI,
    GPU
}

class Posenet(
    val context: Context,
    val filename: String = "posenet_model.tflite",
    val device: Device = Device.GPU
) : AutoCloseable {
    var lastInferenceTimeNanos: Long = -1
        private set

    /** An Interpreter for the TFLite model.   */
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val NUM_LITE_THREADS = 4

    private fun getInterpreter(): Interpreter {
        if (interpreter != null) {
            return interpreter!!
        }
        val options = Interpreter.Options()
        options.setNumThreads(NUM_LITE_THREADS)
        when (device) {
            Device.CPU -> {
            }
            Device.GPU -> {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
            }
            Device.NNAPI -> options.setUseNNAPI(true)
        }
        interpreter = Interpreter(loadModelFile(filename, context), options)
        return interpreter!!
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
    }


    /** Returns value within [0,1].   */
    private fun sigmoid(x: Float): Float {
        return (1.0f / (1.0f + exp(-x)))
    }

    /**
     * Scale the image to a byteBuffer of [-1,1] values.
     */
    private fun initInputArray(bitmap: Bitmap): ByteBuffer {
        val bytesPerChannel = 4
        val inputChannels = 3
        val batchSize = 1
        val inputBuffer = ByteBuffer.allocateDirect(
            batchSize * bytesPerChannel * bitmap.height * bitmap.width * inputChannels
        )
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val mean = 128.0f
        val std = 128.0f
        val intValues = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixelValue in intValues) {
            inputBuffer.putFloat(((pixelValue shr 16 and 0xFF) - mean) / std)
            inputBuffer.putFloat(((pixelValue shr 8 and 0xFF) - mean) / std)
            inputBuffer.putFloat(((pixelValue and 0xFF) - mean) / std)
        }
        return inputBuffer
    }

    /** Preload and memory map the model file, returning a MappedByteBuffer containing the model. */
    private fun loadModelFile(path: String, context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(path)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength
        )
    }

    /**
     * Initializes an outputMap of 1 * x * y * z FloatArrays for the model processing to populate.
     */
    private fun initOutputMap(interpreter: Interpreter): HashMap<Int, Any> {
        val outputMap = HashMap<Int, Any>()

        // 1 * 9 * 9 * 17 contains heatmaps
        val heatmapsShape = interpreter.getOutputTensor(0).shape()
        outputMap[0] = Array(heatmapsShape[0]) {
            Array(heatmapsShape[1]) {
                Array(heatmapsShape[2]) { FloatArray(heatmapsShape[3]) }
            }
        }

        // 1 * 9 * 9 * 34 contains offsets
        val offsetsShape = interpreter.getOutputTensor(1).shape()
        outputMap[1] = Array(offsetsShape[0]) {
            Array(offsetsShape[1]) { Array(offsetsShape[2]) { FloatArray(offsetsShape[3]) } }
        }

        // 1 * 9 * 9 * 32 contains forward displacements
        val displacementsFwdShape = interpreter.getOutputTensor(2).shape()
        outputMap[2] = Array(offsetsShape[0]) {
            Array(displacementsFwdShape[1]) {
                Array(displacementsFwdShape[2]) { FloatArray(displacementsFwdShape[3]) }
            }
        }

        // 1 * 9 * 9 * 32 contains backward displacements
        val displacementsBwdShape = interpreter.getOutputTensor(3).shape()
        outputMap[3] = Array(displacementsBwdShape[0]) {
            Array(displacementsBwdShape[1]) {
                Array(displacementsBwdShape[2]) { FloatArray(displacementsBwdShape[3]) }
            }
        }

        return outputMap
    }

    /**
     * Estimates the pose for a single person.
     * args:
     *      bitmap: image bitmap of frame that should be processed
     * returns:
     *      person: a Person object containing data about keypoint locations and confidence scores
     */

    // 사람의 스켈레톤 및 점수 획득
    @Suppress("UNCHECKED_CAST")
    fun estimateSinglePose(bitmap: Bitmap): Person {
        val estimationStartTimeNanos = SystemClock.elapsedRealtimeNanos()
        val inputArray = arrayOf(initInputArray(bitmap))
        Log.i(
            "posenet",
            String.format(
                "Scaling to [-1,1] took %.2f ms",
                1.0f * (SystemClock.elapsedRealtimeNanos() - estimationStartTimeNanos) / 1_000_000
            )
        )

        val outputMap = initOutputMap(getInterpreter())

        val inferenceStartTimeNanos = SystemClock.elapsedRealtimeNanos()
        getInterpreter().runForMultipleInputsOutputs(inputArray, outputMap)
        lastInferenceTimeNanos = SystemClock.elapsedRealtimeNanos() - inferenceStartTimeNanos
        Log.i(
            "posenet",
            String.format("Interpreter took %.2f ms", 1.0f * lastInferenceTimeNanos / 1_000_000)
        )

        val heatmaps = outputMap[0] as Array<Array<Array<FloatArray>>>
        val offsets = outputMap[1] as Array<Array<Array<FloatArray>>>

        val height = heatmaps[0].size
        val width = heatmaps[0][0].size
        val numKeypoints = heatmaps[0][0][0].size

        // Finds the (row, col) locations of where the keypoints are most likely to be.
        val keypointPositions = Array(numKeypoints) { Pair(0, 0) }
        for (keypoint in 0 until numKeypoints) {
            var maxVal = heatmaps[0][0][0][keypoint]
            var maxRow = 0
            var maxCol = 0
            for (row in 0 until height) {
                for (col in 0 until width) {
                    if (heatmaps[0][row][col][keypoint] > maxVal) {
                        maxVal = heatmaps[0][row][col][keypoint]
                        maxRow = row
                        maxCol = col
                    }
                }
            }
            keypointPositions[keypoint] = Pair(maxRow, maxCol)
        }

        // Calculating the x and y coordinates of the keypoints with offset adjustment.
        val xCoords = IntArray(numKeypoints)
        val yCoords = IntArray(numKeypoints)
        val confidenceScores = FloatArray(numKeypoints)
        keypointPositions.forEachIndexed { idx, position ->
            val positionY = keypointPositions[idx].first
            val positionX = keypointPositions[idx].second
            yCoords[idx] = (
                    position.first / (height - 1).toFloat() * bitmap.height +
                            offsets[0][positionY][positionX][idx]
                    ).toInt()
            xCoords[idx] = (
                    position.second / (width - 1).toFloat() * bitmap.width +
                            offsets[0][positionY]
                                    [positionX][idx + numKeypoints]
                    ).toInt()
            confidenceScores[idx] = sigmoid(heatmaps[0][positionY][positionX][idx])
        }

        // 각도 체크
        val pointAngle = 0;

        val person = Person()
        val keypointList = Array(numKeypoints) { KeyPoint() }
        var totalScore = 0.0f
        enumValues<BodyPart>().forEachIndexed { idx, it ->
            keypointList[idx].bodyPart = it
            keypointList[idx].position.x = xCoords[idx]
            keypointList[idx].position.y = yCoords[idx]
            keypointList[idx].score = confidenceScores[idx]
            Log.d("keypoint.bodyPart", keypointList[idx].bodyPart.toString());
            Log.d("keypoint.position.x", keypointList[idx].position.x.toString());
            Log.d("keypoint.position.y", keypointList[idx].position.y.toString());
            Log.d("keypoint.score", keypointList[idx].score.toString());

            totalScore += confidenceScores[idx]

        }

        //    0. NOSE
        //    1. LEFT_EYE
        //    2. RIGHT_EYE
        //    3. LEFT_EAR
        //    4. RIGHT_EAR
        //    5. LEFT_SHOULDER
        //    6. RIGHT_SHOULDER
        //    7. LEFT_ELBOW
        //    8. RIGHT_ELBOW
        //    9. LEFT_WRIST
        //    10. RIGHT_WRIST
        //    11. LEFT_HIP
        //    12. RIGHT_HIP
        //    13. LEFT_KNEE
        //    14. RIGHT_KNEE
        //    15. LEFT_ANKLE
        //    16. RIGHT_ANKLE

        
        // 실시간 데이터 점수가 낮을경우
        // 한 프레임 비교 안하도록
        Log.d("totalScore", totalScore.toString())
        if(totalScore >= 90){

        }

        
        // 값 수정 X
        person.keyPoints = keypointList.toList()


        // Bodypart 이름(0 ~ 16) / x / y / 신뢰도 출력 가능
//        Log.d("person.keyPoints", person.keyPoints.get(0).bodyPart.toString());
//        Log.d("person.keyPoints", person.keyPoints.get(0).position.x.toString());
//        Log.d("person.keyPoints", person.keyPoints.get(0).position.y.toString());
//        Log.d("person.keyPoints_score", person.keyPoints.get(0).score.toString());

        person.score = totalScore / numKeypoints
        //    Log.d("person.score", person.score.toString());


        // 실시간 데이터 각도 계산

        // LEFT_SIDE_Arm
        var LEFT_SIDE_Arm_dy = person.keyPoints.get(9).position.y - person.keyPoints.get(5).position.y
        var LEFT_SIDE_Arm_dx =
            person.keyPoints.get(9).position.x - person.keyPoints.get(5).position.x
        var LEFT_SIDE_Arm_angle =
            Math.atan2(LEFT_SIDE_Arm_dy.toDouble(), LEFT_SIDE_Arm_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("LEFT_SIDE_Arm", LEFT_SIDE_Arm_angle.toString());

        // LEFT_SIDE_Leg
        var LEFT_SIDE_Leg_dy = person.keyPoints.get(15).position.y - person.keyPoints.get(11).position.y
        var LEFT_SIDE_Leg_dx =
            person.keyPoints.get(15).position.x - person.keyPoints.get(11).position.x
        var LEFT_SIDE_Leg_angle =
            Math.atan2(LEFT_SIDE_Leg_dy.toDouble(), LEFT_SIDE_Leg_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("LEFT_SIDE_Leg", LEFT_SIDE_Leg_angle.toString());

        // RIGHT_SIDE_Arm
        var RIGHT_SIDE_Arm_dy = person.keyPoints.get(6).position.y - person.keyPoints.get(10).position.y
        var RIGHT_SIDE_Arm_dx =
            person.keyPoints.get(6).position.x - person.keyPoints.get(10).position.x
        var RIGHT_SIDE_Arm_angle =
            Math.atan2(RIGHT_SIDE_Arm_dy.toDouble(), RIGHT_SIDE_Arm_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("RIGHT_SIDE_Arm", RIGHT_SIDE_Arm_angle.toString());

        // RIGHT_SIDE_Leg
        var RIGHT_SIDE_Leg_dy = person.keyPoints.get(16).position.y - person.keyPoints.get(12).position.y
        var RIGHT_SIDE_Leg_dx =
            person.keyPoints.get(16).position.x - person.keyPoints.get(12).position.x
        var RIGHT_SIDE_Leg_angle =
            Math.atan2(RIGHT_SIDE_Leg_dy.toDouble(), RIGHT_SIDE_Leg_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("RIGHT_SIDE_Leg", RIGHT_SIDE_Leg_angle.toString());


        // LEFT_ForeArm
        var LEFT_ForeArm_dy =
            person.keyPoints.get(9).position.y - person.keyPoints.get(7).position.y
        var LEFT_ForeArm_dx =
            person.keyPoints.get(9).position.x - person.keyPoints.get(7).position.x
        var LEFT_ForeArm_angle =
            Math.atan2(LEFT_ForeArm_dy.toDouble(), LEFT_ForeArm_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("LEFT_ForeArm", LEFT_ForeArm_angle.toString());

        // LEFT_Arm
        var LEFT_Arm_dy = person.keyPoints.get(7).position.y - person.keyPoints.get(5).position.y
        var LEFT_Arm_dx = person.keyPoints.get(7).position.x - person.keyPoints.get(5).position.x
        var LEFT_Arm_angle =
            Math.atan2(LEFT_Arm_dy.toDouble(), LEFT_Arm_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("LEFT_Arm", LEFT_Arm_angle.toString());

        // LEFT_Body
        var LEFT_Body_dy = person.keyPoints.get(5).position.y - person.keyPoints.get(11).position.y
        var LEFT_Body_dx = person.keyPoints.get(5).position.x - person.keyPoints.get(11).position.x
        var LEFT_Body_angle =
            Math.atan2(LEFT_Body_dy.toDouble(), LEFT_Body_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("LEFT_Body", LEFT_Body_angle.toString());

        // LEFT_KneeUp
        var LEFT_KneeUp_dy =
            person.keyPoints.get(11).position.y - person.keyPoints.get(13).position.y
        var LEFT_KneeUp_dx =
            person.keyPoints.get(11).position.x - person.keyPoints.get(13).position.x
        var LEFT_KneeUp_angle =
            Math.atan2(LEFT_KneeUp_dy.toDouble(), LEFT_KneeUp_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("LEFT_KneeUp", LEFT_KneeUp_angle.toString());

        // LEFT_KneeDown
        var LEFT_KneeDown_dy =
            person.keyPoints.get(13).position.y - person.keyPoints.get(15).position.y
        var LEFT_KneeDown_dx =
            person.keyPoints.get(13).position.x - person.keyPoints.get(15).position.x
        var LEFT_KneeDown_angle =
            Math.atan2(LEFT_KneeDown_dy.toDouble(), LEFT_KneeDown_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("LEFT_KneeDown", LEFT_KneeDown_angle.toString());

        // RIGHT_ForeArm
        var RIGHT_ForeArm_dy =
            person.keyPoints.get(10).position.y - person.keyPoints.get(8).position.y
        var RIGHT_ForeArm_dx =
            person.keyPoints.get(10).position.x - person.keyPoints.get(8).position.x
        var RIGHT_ForeArm_angle =
            Math.atan2(RIGHT_ForeArm_dy.toDouble(), RIGHT_ForeArm_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("RIGHT_ForeArm", RIGHT_ForeArm_angle.toString());

        // RIGHT_Arm
        var RIGHT_Arm_dy = person.keyPoints.get(8).position.y - person.keyPoints.get(6).position.y
        var RIGHT_Arm_dx = person.keyPoints.get(8).position.x - person.keyPoints.get(6).position.x
        var RIGHT_Arm_angle =
            Math.atan2(RIGHT_Arm_dy.toDouble(), RIGHT_Arm_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("RIGHT_Arm", RIGHT_Arm_angle.toString());

        // RIGHT_Body
        var RIGHT_Body_dy = person.keyPoints.get(6).position.y - person.keyPoints.get(12).position.y
        var RIGHT_Body_dx = person.keyPoints.get(6).position.x - person.keyPoints.get(12).position.x
        var RIGHT_Body_angle =
            Math.atan2(RIGHT_Body_dy.toDouble(), RIGHT_Body_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("RIGHT_Body", RIGHT_Body_angle.toString());

        // RIGHT_KneeUp
        var RIGHT_KneeUp_dy =
            person.keyPoints.get(12).position.y - person.keyPoints.get(14).position.y
        var RIGHT_KneeUp_dx =
            person.keyPoints.get(12).position.x - person.keyPoints.get(14).position.x
        var RIGHT_KneeUp_angle =
            Math.atan2(RIGHT_KneeUp_dy.toDouble(), RIGHT_KneeUp_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("RIGHT_KneeUp", RIGHT_KneeUp_angle.toString());

        // RIGHT_KneeDown
        var RIGHT_KneeDown_dy =
            person.keyPoints.get(14).position.y - person.keyPoints.get(16).position.y
        var RIGHT_KneeDown_dx =
            person.keyPoints.get(14).position.x - person.keyPoints.get(16).position.x
        var RIGHT_KneeDown_angle = Math.atan2(
            RIGHT_KneeDown_dy.toDouble(),
            RIGHT_KneeDown_dx.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("RIGHT_KneeDown", RIGHT_KneeDown_angle.toString());

        // CENTER_Body
        var CENTER_Body_dy = person.keyPoints.get(5).position.y - person.keyPoints.get(6).position.y
        var CENTER_Body_dx = person.keyPoints.get(5).position.x - person.keyPoints.get(6).position.x
        var CENTER_Body_angle =
            Math.atan2(CENTER_Body_dy.toDouble(), CENTER_Body_dx.toDouble()) * (180.0 / Math.PI)
        Log.d("CENTER_Body", CENTER_Body_angle.toString());

        // CENTER_Shoulder
        var CENTER_Shoulder_dy =
            person.keyPoints.get(11).position.y - person.keyPoints.get(12).position.y
        var CENTER_Shoulder_dx =
            person.keyPoints.get(11).position.x - person.keyPoints.get(12).position.x
        var CENTER_Shoulder_angle = Math.atan2(
            CENTER_Shoulder_dy.toDouble(),
            CENTER_Shoulder_dx.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("CENTER_Shoulder", CENTER_Shoulder_angle.toString());




        jsonObjectsExample()
        frameCounter++;

        return person
    }



    // Json data (선생 데이터) 가져오기
    @SuppressLint("LongLogTag")
    fun jsonObjectsExample() {

        // 파일 경로 세팅 완료
        var filePathFirst = "jsons/sidejack/"
        var filePathFinal = ".json"

        // 실제
//        var fileJsonPath = filePathFirst+ frameCounter + filePathFinal

        // test용
        var fileJsonPath = filePathFirst+ 2 + filePathFinal

        Log.d("JSON_FRAME_COUNTER", frameCounter.toString());
        Log.d("파일 경로 확인",fileJsonPath)

        // open 해결 => 0 ~ 160 Frame의 정보 가져오도록

        val inputStream = context.assets.open("$fileJsonPath")
        val br = BufferedReader(InputStreamReader(inputStream))
        val str = br.readText()
        val jo = JSONObject(str)

        // 객체 불러옴
        val jArray = jo.getJSONArray("FRAME2")
//        val jArray = jo.getJSONArray("FRAME$frameCounter")

        for (i in 0 until jArray.length()) {
            val obj = jArray.getJSONObject(i)
            val json_bodypart = obj.getString("NAME")
            val json_x = obj.getDouble("x")
            val json_y = obj.getDouble("y")
            val json_score = obj.getDouble("score")

            Log.d("JSON_DATA", "NAME($i): $json_bodypart")
            Log.d("JSON_DATA", "x($i): $json_x")
            Log.d("JSON_DATA", "y($i): $json_y")
            Log.d("JSON_DATA", "y($i): $json_score")
        }

        // 12가지 Json 각도 체크
        var Json_LEFT_ForeArm: Int = 0
        var Json_LEFT_Arm: Int = 0
        var Json_LEFT_Body: Int = 0
        var Json_LEFT_KneeUp: Int = 0
        var Json_LEFT_KneeDown: Int = 0
        var Json_Center_Body: Int = 0
        var Json_Center_Shoulder: Int = 0
        var Json_RIGHT_ForeArm: Int = 0
        var Json_RIGHT_Arm: Int = 0
        var Json_RIGHT_Body: Int = 0
        var Json_RIGHT_KneeUp: Int = 0
        var Json_RIGHT_KneeDown: Int = 0




        // JSON_LEFT_SIDE_Arm
        val JSON_LEFT_SIDE_Arm_X = jArray.getJSONObject(9).getInt("x") - jArray.getJSONObject(5).getInt(
            "x"
        )
        val JSON_LEFT_SIDE_Arm_Y = jArray.getJSONObject(9).getInt("y") - jArray.getJSONObject(5).getInt(
            "y"
        )
        val JSON_LEFT_SIDE_Arm_angle = Math.atan2(
            JSON_LEFT_SIDE_Arm_Y.toDouble(),
            JSON_LEFT_SIDE_Arm_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("JSON_LEFT_SIDE_Arm_angle", JSON_LEFT_SIDE_Arm_angle.toString());

        // JSON_LEFT_SIDE_Leg
        val JSON_LEFT_SIDE_Leg_X = jArray.getJSONObject(15).getInt("x") - jArray.getJSONObject(11).getInt(
            "x"
        )
        val JSON_LEFT_SIDE_Leg_Y = jArray.getJSONObject(15).getInt("y") - jArray.getJSONObject(11).getInt(
            "y"
        )
        val JSON_LEFT_SIDE_Leg_angle = Math.atan2(
            JSON_LEFT_SIDE_Leg_Y.toDouble(),
            JSON_LEFT_SIDE_Leg_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("JSON_LEFT_SIDE_Leg_angle", JSON_LEFT_SIDE_Leg_angle.toString());

        // JSON_RIGHT_SIDE_Arm
        val JSON_RIGHT_SIDE_Arm_X = jArray.getJSONObject(6).getInt("x") - jArray.getJSONObject(10).getInt(
            "x"
        )
        val JSON_RIGHT_SIDE_Arm_Y = jArray.getJSONObject(6).getInt("y") - jArray.getJSONObject(10).getInt(
            "y"
        )
        val JSON_RIGHT_SIDE_Arm_angle = Math.atan2(
            JSON_RIGHT_SIDE_Arm_Y.toDouble(),
            JSON_RIGHT_SIDE_Arm_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("JSON_RIGHT_SIDE_Arm_angle", JSON_RIGHT_SIDE_Arm_angle.toString());

        // JSON_RIGHT_SIDE_Leg
        val JSON_RIGHT_SIDE_Leg_X = jArray.getJSONObject(16).getInt("x") - jArray.getJSONObject(12).getInt(
            "x"
        )
        val JSON_RIGHT_SIDE_Leg_Y = jArray.getJSONObject(16).getInt("y") - jArray.getJSONObject(12).getInt(
            "y"
        )
        val JSON_RIGHT_SIDE_Leg_angle = Math.atan2(
            JSON_RIGHT_SIDE_Leg_Y.toDouble(),
            JSON_RIGHT_SIDE_Leg_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("JSON_RIGHT_SIDE_Leg_angle", JSON_RIGHT_SIDE_Leg_angle.toString());

        // Json_Left_ForeArm
        val Json_LEFT_ForeArm_X = jArray.getJSONObject(9).getInt("x") - jArray.getJSONObject(7).getInt(
            "x"
        )
//        Log.d("Json_Left_ForeArm_X", Json_LEFT_ForeArm_X.toString());
        val Json_LEFT_ForeArm_Y = jArray.getJSONObject(9).getInt("y") - jArray.getJSONObject(7).getInt(
            "y"
        )
//        Log.d("Json_Left_ForeArm_Y", Json_LEFT_ForeArm_Y.toString());
        val Json_LEFT_ForeArm_angle = Math.atan2(
            Json_LEFT_ForeArm_Y.toDouble(),
            Json_LEFT_ForeArm_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_Left_ForeArm_angle", Json_LEFT_ForeArm_angle.toString());

        // Json_LEFT_Arm
        val Json_LEFT_Arm_X = jArray.getJSONObject(7).getInt("x") - jArray.getJSONObject(5).getInt("x")
//        Log.d("Json_LEFT_Arm_X", Json_LEFT_Arm_X.toString());
        val Json_LEFT_Arm_Y = jArray.getJSONObject(7).getInt("y") - jArray.getJSONObject(5).getInt("y")
//        Log.d("Json_LEFT_Arm_Y", Json_LEFT_Arm_Y.toString());
        val Json_LEFT_Arm_angle = Math.atan2(Json_LEFT_Arm_Y.toDouble(), Json_LEFT_Arm_X.toDouble()) * (180.0 / Math.PI)
        Log.d("Json_LEFT_Arm_angle", Json_LEFT_Arm_angle.toString());

        // Json_LEFT_Body
        val Json_LEFT_Body_X = jArray.getJSONObject(5).getInt("x") - jArray.getJSONObject(11).getInt(
            "x"
        )
//        Log.d("Json_LEFT_Body_X", Json_LEFT_Body_X.toString());
        val Json_LEFT_Body_Y = jArray.getJSONObject(5).getInt("y") - jArray.getJSONObject(11).getInt(
            "y"
        )
//        Log.d("Json_LEFT_Body_Y", Json_LEFT_Body_Y.toString());
        val Json_LEFT_Body_angle = Math.atan2(
            Json_LEFT_Body_Y.toDouble(),
            Json_LEFT_Body_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_LEFT_Body_angle", Json_LEFT_Body_angle.toString());

        // Json_LEFT_KneeUp
        val Json_LEFT_KneeUp_X = jArray.getJSONObject(11).getInt("x") - jArray.getJSONObject(13).getInt(
            "x"
        )
//        Log.d("Json_LEFT_KneeUp_X", Json_LEFT_KneeUp_X.toString());
        val Json_LEFT_KneeUp_Y = jArray.getJSONObject(11).getInt("y") - jArray.getJSONObject(13).getInt(
            "y"
        )
//        Log.d("Json_LEFT_KneeUp_Y", Json_LEFT_KneeUp_Y.toString());
        val Json_LEFT_KneeUp_angle = Math.atan2(
            Json_LEFT_KneeUp_Y.toDouble(),
            Json_LEFT_KneeUp_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_LEFT_KneeUp_angle", Json_LEFT_KneeUp_angle.toString());

        // Json_LEFT_KneeDown
        val Json_LEFT_KneeDown_X = jArray.getJSONObject(13).getInt("x") - jArray.getJSONObject(15).getInt(
            "x"
        )
//        Log.d("Json_LEFT_KneeDown_X", Json_LEFT_KneeDown_X.toString());
        val Json_LEFT_KneeDown_Y = jArray.getJSONObject(13).getInt("y") - jArray.getJSONObject(15).getInt(
            "y"
        )
//        Log.d("Json_LEFT_KneeDown_Y", Json_LEFT_KneeDown_Y.toString());
        val Json_LEFT_KneeDown_angle = Math.atan2(
            Json_LEFT_KneeDown_Y.toDouble(),
            Json_LEFT_KneeDown_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_LEFT_KneeDown_angle", Json_LEFT_KneeDown_angle.toString());


        // Json_RIGHT_ForeArm
        val Json_RIGHT_ForeArm_X = jArray.getJSONObject(10).getInt("x") - jArray.getJSONObject(8).getInt(
            "x"
        )
//        Log.d("Json_RIGHT_ForeArm_X", Json_RIGHT_ForeArm_X.toString());
        val Json_RIGHT_ForeArm_Y = jArray.getJSONObject(10).getInt("y") - jArray.getJSONObject(8).getInt(
            "y"
        )
//        Log.d("Json_RIGHT_ForeArm_Y", Json_LEFT_ForeArm_Y.toString());
        val Json_RIGHT_ForeArm_angle = Math.atan2(
            Json_RIGHT_ForeArm_Y.toDouble(),
            Json_RIGHT_ForeArm_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_RIGHT_ForeArm_angle", Json_LEFT_ForeArm_angle.toString());

        // Json_RIGHT_Arm
        val Json_RIGHT_Arm_X = jArray.getJSONObject(8).getInt("x") - jArray.getJSONObject(6).getInt(
            "x"
        )
//        Log.d("Json_RIGHT_Arm_X", Json_RIGHT_Arm_X.toString());
        val Json_RIGHT_Arm_Y = jArray.getJSONObject(8).getInt("y") - jArray.getJSONObject(6).getInt(
            "y"
        )
//        Log.d("Json_RIGHT_Arm_Y", Json_RIGHT_Arm_Y.toString());
        val Json_RIGHT_Arm_angle = Math.atan2(
            Json_RIGHT_Arm_Y.toDouble(),
            Json_RIGHT_Arm_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_RIGHT_Arm_angle", Json_RIGHT_Arm_angle.toString());

        // Json_RIGHT_Body
        val Json_RIGHT_Body_X = jArray.getJSONObject(6).getInt("x") - jArray.getJSONObject(12).getInt(
            "x"
        )
//        Log.d("Json_RIGHT_Body_X", Json_RIGHT_Body_X.toString());
        val Json_RIGHT_Body_Y = jArray.getJSONObject(6).getInt("y") - jArray.getJSONObject(12).getInt(
            "y"
        )
//        Log.d("Json_RIGHT_Body_Y", Json_RIGHT_Body_Y.toString());
        val Json_RIGHT_Body_angle = Math.atan2(
            Json_RIGHT_Body_Y.toDouble(),
            Json_RIGHT_Body_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_RIGHT_Body_angle", Json_RIGHT_Body_angle.toString());

        // Json_RIGHT_KneeUp
        val Json_RIGHT_KneeUp_X = jArray.getJSONObject(12).getInt("x") - jArray.getJSONObject(14).getInt(
            "x"
        )
//        Log.d("Json_RIGHT_KneeUp_X", Json_RIGHT_KneeUp_X.toString());
        val Json_RIGHT_KneeUp_Y = jArray.getJSONObject(12).getInt("y") - jArray.getJSONObject(14).getInt(
            "y"
        )
//        Log.d("Json_RIGHT_KneeUp_Y", Json_RIGHT_KneeUp_Y.toString());
        val Json_RIGHT_KneeUp_angle = Math.atan2(
            Json_RIGHT_KneeUp_Y.toDouble(),
            Json_RIGHT_KneeUp_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_RIGHT_KneeUp_angle", Json_RIGHT_KneeUp_angle.toString());

        // Json_RIGHT_KneeDown
        val Json_RIGHT_KneeDown_X = jArray.getJSONObject(14).getInt("x") - jArray.getJSONObject(16).getInt(
            "x"
        )
//        Log.d("Json_RIGHT_KneeDown_X", Json_RIGHT_KneeDown_X.toString());
        val Json_RIGHT_KneeDown_Y = jArray.getJSONObject(14).getInt("y") - jArray.getJSONObject(16).getInt(
            "y"
        )
//        Log.d("Json_RIGHT_KneeDown_Y", Json_RIGHT_KneeDown_Y.toString());
        val Json_RIGHT_KneeDown_angle = Math.atan2(
            Json_RIGHT_KneeDown_Y.toDouble(),
            Json_RIGHT_KneeDown_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_RIGHT_KneeDown_angle", Json_RIGHT_KneeDown_angle.toString());

        // Json_CENTER_Body
        val Json_CENTER_Body_X = jArray.getJSONObject(5).getInt("x") - jArray.getJSONObject(6).getInt(
            "x"
        )
//        Log.d("Json_CENTER_Body_X", Json_CENTER_Body_X.toString());
        val Json_CENTER_Body_Y = jArray.getJSONObject(5).getInt("y") - jArray.getJSONObject(6).getInt(
            "y"
        )
//        Log.d("Json_CENTER_Body_Y", Json_CENTER_Body_Y.toString());
        val Json_CENTER_Body_angle = Math.atan2(
            Json_CENTER_Body_Y.toDouble(),
            Json_CENTER_Body_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_CENTER_Body_angle", Json_CENTER_Body_angle.toString());

        // Json_CENTER_Shoulder
        val Json_CENTER_Shoulder_X = jArray.getJSONObject(11).getInt("x") - jArray.getJSONObject(12).getInt(
            "x"
        )
//        Log.d("Json_CENTER_Shoulder_X", Json_CENTER_Shoulder_X.toString());
        val Json_CENTER_Shoulder_Y = jArray.getJSONObject(11).getInt("y") - jArray.getJSONObject(12).getInt(
            "y"
        )
//        Log.d("Json_CENTER_Shoulder_Y", Json_CENTER_Shoulder_Y.toString());
        val Json_CENTER_Shoulder_angle = Math.atan2(
            Json_CENTER_Shoulder_Y.toDouble(),
            Json_CENTER_Shoulder_X.toDouble()
        ) * (180.0 / Math.PI)
        Log.d("Json_CENTER_Shoulder_angle", Json_CENTER_Shoulder_angle.toString());

    }

}
