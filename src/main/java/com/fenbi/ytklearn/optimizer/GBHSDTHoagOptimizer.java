/**
*
* Copyright (c) 2017 ytk-learn https://github.com/yuantiku
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:

* The above copyright notice and this permission notice shall be included in all
* copies or substantial portions of the Software.

* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
* SOFTWARE.
*/

package com.fenbi.ytklearn.optimizer;

import com.fenbi.mp4j.exception.Mp4jException;
import com.fenbi.mp4j.operand.Operands;
import com.fenbi.mp4j.operator.Operators;
import com.fenbi.ytklearn.dataflow.GBHSDTDataFlow;
import com.fenbi.ytklearn.dataflow.GBMLRDataFlow;
import com.fenbi.ytklearn.dataflow.ContinuousDataFlow;
import com.fenbi.ytklearn.utils.MathUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * @author xialong
 */

public class GBHSDTHoagOptimizer extends HoagOptimizer {
    private final int K;
    private final int L;

    private double wx[];
    private double mu[];

    private float[][] z;
    private float[][] ztest;
    private double gx[];
    private final BitSet[]randMask;
    private final BitSet featureMask;
    private final double learningRate;

    private final GBMLRDataFlow.Type type;

    private final double randomSampleRate;
    private final double []samples;
    private final GBHSDTDataFlow gbhsdtDataFlow;
    private final List<String> trainIterOtherInfos = new ArrayList<>();
    private final List<String> testIterOtherInfos = new ArrayList<>();

    public GBHSDTHoagOptimizer(String modelName,
                               ContinuousDataFlow dataFlow,
                               int threadIdx) throws Exception {
        super(modelName, dataFlow, threadIdx);

        GBHSDTDataFlow gbhsdtDataFlow = (GBHSDTDataFlow)dataFlow;
        this.gbhsdtDataFlow = gbhsdtDataFlow;

        this.K = gbhsdtDataFlow.getK();
        this.L = gbhsdtDataFlow.getL();
        this.wx = new double[2 * K - 1];
        this.mu = new double[2 * K - 1];
        this.samples = new double[K];
        this.gx = new double[K];
        this.z = ((GBMLRDataFlow.GBMLRCoreData)threadTrainCoreData).getZ();

        this.randMask = ((GBMLRDataFlow.GBMLRCoreData)threadTrainCoreData).getRandMask();
        this.featureMask = ((GBMLRDataFlow.GBMLRCoreData)threadTrainCoreData).getFeatureMask();
        this.randomSampleRate = gbhsdtDataFlow.getRandomSampleRate();
        this.learningRate = gbhsdtDataFlow.getLearningRate();
        this.type = gbhsdtDataFlow.getType();

        if (hasTestData) {
            this.ztest = ((GBMLRDataFlow.GBMLRCoreData)threadTestCoreData).getZ();
        }

//        l2[0] /= K;
//        l1[0] /= K;
//
//        l2[1] /= (K - 1);
//        l1[1] /= (K - 1);
    }


    @Override
    public int[] getRegularStart() {
        int []start = new int[2];
        start[0] = 0;
        if (modelParams.need_bias) {
            start[1] = 2 * K - 1;
        } else {
            start[1] = K;
        }
        return start;
    }

    @Override
    public int[] getRegularEnd() {
        int []end = new int[2];
        end[0] = K;
        end[1] = dim;
        return end;
    }

    @Override
    protected String extraInfo() {
        return "[round=" + (gbhsdtDataFlow.getFinishedTreeNum() + 1) + "] ";
    }

    @Override
    protected void otherTrainHandle(int iter) throws Mp4jException {
        for (String info : trainIterOtherInfos) {
            importantInfo(iter, info);
        }
        trainIterOtherInfos.clear();

    }

    @Override
    protected void otherTestHandle(int iter) throws Mp4jException {
        for (String info : testIterOtherInfos) {
            importantInfo(iter, info);
        }
        testIterOtherInfos.clear();
    }

    @Override
    public double calcPureLossAndGrad(float[] w, float[] g, int iter) throws Exception {

        double rfLoss = 0.0;
        double loss = 0.0;
        int stride = K - 1;
        int vstart = K;
        double compensate = 1.0 / randomSampleRate;
        for (int i = 0; i < dim; i++) {
            g[i] = 0.0f;
        }

        int treeNum = gbhsdtDataFlow.getFinishedTreeNum() + 1;

        for (int i = 0; i < K; i++) {
            samples[i] = 0.0;
        }

        for (int k = 0; k < threadTrainCoreData.cursor2d; k++) {
            int lsNumInt = realNum[k];
            for (int i = 0; i < lsNumInt; i++) {
                if (!randMask[k].get(i)) {
                    continue;
                }
                double wei = weight[k][i];
                wei *= compensate;

                // xv, xw
                for (int p = 0; p < stride; p++) {
                    wx[p] = 0.0;
                }
                for (int j = xidx[k][i]; j < xidx[k][i + 1]; j+=2) {
                    if (!featureMask.get(x[k][j])) {
                        continue;
                    }
                    double fval = Float.intBitsToFloat(x[k][j+1]);
                    int idx = vstart + x[k][j] * stride;
                    for (int p = 0; p < stride; p++) {
                        wx[p] += w[idx + p] * fval;
                    }
                }

                for (int j = 0; j < stride; j++) {
                    wx[j] = MathUtils.logistic(wx[j]);
                }

                for (int j = stride; j < 2 * K - 1; j++) {
                    wx[j] = w[j - stride];
                }

                // calc mu
                for (int j = stride; j < 2 * K - 1; j++) {
                    int gidx = j - stride;
                    gx[gidx] = 1.0;
                    int prevIdx = j + 1;
                    int curIdx;
                    for (int p = 0; p < L; p++) {
                        curIdx = prevIdx >>> 1;
                        gx[gidx] *= ((prevIdx & 1) == 0 ? wx[curIdx - 1] : 1.0 - wx[curIdx - 1]);
                        prevIdx = curIdx;
                        if (curIdx == 1) {
                            break;
                        }
                    }
                    samples[gidx] += gx[gidx];
                    mu[j] = gx[gidx] * wx[j];

                }

                for (int j = stride - 1; j >= 0; j--) {
                    int idx = ((j + 1) << 1) - 1;
                    mu[j] = mu[idx] + mu[idx + 1];
                }

                double fx = (type == GBMLRDataFlow.Type.RF) ? mu[0] : z[k][i] + mu[0];
                loss += wei * lossFunction.loss(fx, y[k][i]);
                if (type == GBMLRDataFlow.Type.RF) {
                    rfLoss += wei * lossFunction.loss((z[k][i] + mu[0]) / treeNum, y[k][i]);
                    predict[k][i] = (float) lossFunction.predict((z[k][i] + mu[0]) / treeNum);
                } else {
                    predict[k][i] = (float) lossFunction.predict(fx);
                }

                // grad
                double gradscalar = wei * lossFunction.firstDerivative(fx, y[k][i]);
                for (int j = xidx[k][i]; j < xidx[k][i + 1]; j+=2) {
                    if (!featureMask.get(x[k][j])) {
                        continue;
                    }
                    int idx = vstart + x[k][j] * stride;
                    double fval = Float.intBitsToFloat(x[k][j+1]);
                    // w, v' grad
                    for (int p = 1; p < K; p++) {
                        g[idx + p - 1] += (float)(gradscalar * (mu[(p << 1) - 1] - wx[p - 1] * mu[p - 1]) * fval);
                    }
                }

                for (int p = 0; p < K; p++) {
                    g[p] += gradscalar * gx[p];
                }
            }
        }

        if (type == GBMLRDataFlow.Type.RF) {
            rfLoss = comm.allreduce(rfLoss, Operands.DOUBLE_OPERAND(), Operators.Double.SUM);
            trainIterOtherInfos.add("train loss(random forest):" + rfLoss / gWeightTrainNum);
        }

        comm.allreduceArray(samples, Operands.DOUBLE_OPERAND(), Operators.Double.SUM, 0, samples.length);
        double sampleSum = 0.0;
        for (int i = 0; i < K; i++) {
            sampleSum += samples[i];
        }

        for (int i = 0; i < K; i++) {
            samples[i] /= sampleSum;
        }

        trainIterOtherInfos.add("all samples:" + sampleSum + ", ideal avg samples:" + sampleSum / (comm.getSlaveNum() * comm.getThreadNum()) + ", samples distribution:" + Arrays.toString(samples));
        return loss;
    }

    @Override
    public double calTestPureLossAndGrad(float []wtest, float []gtest, int iter, boolean needCalcGrad) throws Mp4jException {
        if (!hasTestData) {
            return -1.0;
        }

        double rfLoss = 0.0;
        double loss = 0.0;
        int stride = K - 1;
        int vstart = K;
        if (needCalcGrad) {
            for (int i = 0; i < dim; i++) {
                gtest[i] = 0.0f;
            }
        }

        int treeNum = gbhsdtDataFlow.getFinishedTreeNum() + 1;

        for (int k = 0; k < threadTestCoreData.cursor2d; k++) {
            int lsNumInt = realNumtest[k];
            for (int i = 0; i < lsNumInt; i++) {
                double wei = weighttest[k][i];

                // xv, xw
                for (int p = 0; p < stride; p++) {
                    wx[p] = 0.0;
                }
                for (int j = xidxtest[k][i]; j < xidxtest[k][i + 1]; j+=2) {
                    if (!featureMask.get(xtest[k][j])) {
                        continue;
                    }
                    double fval = Float.intBitsToFloat(xtest[k][j+1]);
                    int idx = vstart + xtest[k][j] * stride;
                    for (int p = 0; p < stride; p++) {
                        wx[p] += wtest[idx + p] * fval;
                    }
                }

                for (int j = 0; j < stride; j++) {
                    wx[j] = MathUtils.logistic(wx[j]);
                }

                for (int j = stride; j < 2 * K - 1; j++) {
                    wx[j] = wtest[j - stride];
                }

                // calc mu
                for (int j = stride; j < 2 * K - 1; j++) {
                    int gidx = j - stride;
                    gx[gidx] = 1.0;
                    int prevIdx = j + 1;
                    int curIdx;
                    for (int p = 0; p < L; p++) {
                        curIdx = prevIdx >>> 1;
                        gx[gidx] *= ((prevIdx & 1) == 0 ? wx[curIdx - 1] : 1.0 - wx[curIdx - 1]);
                        prevIdx = curIdx;
                        if (curIdx == 1) {
                            break;
                        }
                    }
                    mu[j] = gx[gidx] * wx[j];

                }

                for (int j = stride - 1; j >= 0; j--) {
                    int idx = ((j + 1) << 1) - 1;
                    mu[j] = mu[idx] + mu[idx + 1];
                }

                double fx = (type == GBMLRDataFlow.Type.RF) ? mu[0] : ztest[k][i] + mu[0];
                loss += wei * lossFunction.loss(fx, ytest[k][i]);
                if (type == GBMLRDataFlow.Type.RF) {
                    rfLoss += wei * lossFunction.loss((ztest[k][i] + mu[0]) / treeNum, ytest[k][i]);
                    predicttest[k][i] = (float) lossFunction.predict((ztest[k][i] + mu[0]) / treeNum);
                } else {
                    predicttest[k][i] = (float) lossFunction.predict(fx);
                }

                // grad
                if (needCalcGrad) {
                    double gradscalar = wei * lossFunction.firstDerivative(fx, ytest[k][i]);
                    for (int j = xidxtest[k][i]; j < xidxtest[k][i + 1]; j+=2) {
                        if (!featureMask.get(xtest[k][j])) {
                            continue;
                        }
                        int idx = vstart + xtest[k][j] * stride;
                        double fval = Float.intBitsToFloat(xtest[k][j+1]);
                        // w, v' grad
                        for (int p = 1; p < K; p++) {
                            gtest[idx + p - 1] += (float)(gradscalar * (mu[(p << 1) - 1] - wx[p - 1] * mu[p - 1]) * fval);
                        }
                    }

                    for (int p = 0; p < K; p++) {
                        gtest[p] += gradscalar * gx[p];
                    }
                }

            }
        }

        if (type == GBMLRDataFlow.Type.RF) {
            rfLoss = comm.allreduce(rfLoss, Operands.DOUBLE_OPERAND(), Operators.Double.SUM);
            testIterOtherInfos.add("test loss(random forest):" + rfLoss / gWeightTestNum);
        }

        return loss;
    }
}
