/*
 * Copyright (c) 2019, NVIDIA CORPORATION. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  * Neither the name of NVIDIA CORPORATION nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.nvidia.grcuda.gpu;

import java.util.ArrayList;
import com.nvidia.grcuda.gpu.UnsafeHelper.PointerArray;
import com.nvidia.grcuda.gpu.UnsafeHelper.StringObject;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;

public class NVRuntimeCompiler {

    public final CUDARuntime runtime;
    private final Node executeNode = Message.EXECUTE.createNode();
    private final Node unboxNode = Message.UNBOX.createNode();
    private static final int MAX_LOG_SIZE = 1 << 20;

    public NVRuntimeCompiler(CUDARuntime runtime) {
        this.runtime = runtime;
    }

    public PTXKernel compileKernel(String code, String kernelName, String moduleName, String... compileOpts) {
        try (NVRTCProgram program = createProgram(code, moduleName)) {
            nvrtcAddNameExpression(program, kernelName);
            NVRTCResult compileResult = nvrtcCompileProgram(program, compileOpts);
            if (compileResult != NVRTCResult.NVRTC_SUCCESS) {
                String compileLog = getProgramLog(program);
                System.err.println("compile result: " + compileResult);
                System.err.println("program log: " + compileLog);
                throw new NVRTCException(compileResult.errorCode, compileLog);
            }
            String loweredKernelName = nvrtcGetLoweredName(program, kernelName);
            String ptx = getPTX(program);
            return new PTXKernel(ptx, kernelName, loweredKernelName);
        }
    }

    public NVRTCProgram createProgram(String source, String programName) {
        NVRTCProgram program = new NVRTCProgram();
        try {
            TruffleObject callable = getSymbol(NVRTCFunction.NVRTC_CREATEPROGRAM);
            Object result = ForeignAccess.sendExecute(executeNode, callable,
                            program.getAddress(), source, programName,
                            0, // number of headers
                            0, // pointer to headers (NULL if numHeaders == 0)
                            0); // pointer to header names (NULL if numHeaders == 0)
            checkNVRTCReturnCode(result, NVRTCFunction.NVRTC_CREATEPROGRAM.symbolName);
            program.setInitialized();
            return program;
        } catch (InteropException e) {
            program.close();
            throw new RuntimeException(e);
        }
    }

    public void nvrtcAddNameExpression(NVRTCProgram program, String name) {
        NVRTCFunction function = NVRTCFunction.NVRTC_ADDNAMEDEXPRESSION;
        try {
            TruffleObject callable = getSymbol(function);
            Object result = ForeignAccess.sendExecute(executeNode, callable, program.getValue(), name);
            checkNVRTCReturnCode(result, function.symbolName);
        } catch (InteropException e) {
            throw new RuntimeException(e);
        }
    }

    public NVRTCResult nvrtcCompileProgram(NVRTCProgram program, String... opts) {
        if (opts.length == 0) {
            return nvrtcCompileProgramInternal(program, 0, 0L);
        } else {
            ArrayList<StringObject> optCStrings = new ArrayList<>(opts.length);
            try (UnsafeHelper.PointerArray optCStringArr = new PointerArray(opts.length)) {
                int idx = 0;
                for (String optString : opts) {
                    UnsafeHelper.StringObject cString = UnsafeHelper.StringObject.fromJavaString(optString);
                    optCStrings.add(cString);
                    optCStringArr.setValueAt(idx, cString.getAddress());
                }
                NVRTCResult result = nvrtcCompileProgramInternal(program, opts.length,
                                optCStringArr.getAddress());
                for (UnsafeHelper.StringObject s : optCStrings) {
                    s.close();
                }
                return result;
            }
        }
    }

    private NVRTCResult nvrtcCompileProgramInternal(NVRTCProgram program, int numOpts, long addressOfOptStringPointerArray) {
        NVRTCFunction function = NVRTCFunction.NVRTC_COMPILEPROGRAM;
        try {
            TruffleObject callable = getSymbol(function);
            Object result = ForeignAccess.sendExecute(executeNode, callable,
                            program.getValue(),
                            numOpts, addressOfOptStringPointerArray);
            return toNVRTCResult(result);
        } catch (InteropException e) {
            throw new RuntimeException(e);
        }
    }

    public String getProgramLog(NVRTCProgram program) {
        int logSize;
        try (UnsafeHelper.Integer64Object sizeBytes = new UnsafeHelper.Integer64Object()) {
            try {
                sizeBytes.setValue(0);
                TruffleObject callable = getSymbol(NVRTCFunction.NVRTC_GETPROGRAMLOGSIZE);
                Object result = ForeignAccess.sendExecute(executeNode, callable, program.getValue(), sizeBytes.getAddress());
                checkNVRTCReturnCode(result, NVRTCFunction.NVRTC_GETPROGRAMLOGSIZE.symbolName);
                logSize = (int) sizeBytes.getValue();
                if (logSize < 0 || logSize > MAX_LOG_SIZE) {  // upper limit to prevent OoM
                    throw new RuntimeException("Invalid string allocation length " + logSize + ", expected <1 MB");
                }
            } catch (InteropException e) {
                throw new RuntimeException(e);
            }
        }
        try (UnsafeHelper.StringObject buffer = UnsafeHelper.createStringObject(logSize)) {
            try {
                TruffleObject callable = getSymbol(NVRTCFunction.NVRTC_GETPROGRAMLOG);
                Object result = ForeignAccess.sendExecute(executeNode, callable, program.getValue(), buffer.getAddress());
                checkNVRTCReturnCode(result, NVRTCFunction.NVRTC_GETPROGRAMLOG.symbolName);
                return buffer.getZeroTerminatedString();
            } catch (InteropException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public String getPTX(NVRTCProgram program) {
        int ptxSize;
        try (UnsafeHelper.Integer64Object sizeBytes = new UnsafeHelper.Integer64Object()) {
            try {
                sizeBytes.setValue(0);
                TruffleObject callable = getSymbol(NVRTCFunction.NVRTC_GETPTXSIZE);
                Object result = ForeignAccess.sendExecute(executeNode, callable, program.getValue(), sizeBytes.getAddress());
                checkNVRTCReturnCode(result, NVRTCFunction.NVRTC_GETPTXSIZE.symbolName);
                ptxSize = (int) sizeBytes.getValue();
                if (ptxSize < 0 || ptxSize > MAX_LOG_SIZE) {  // upper limit to prevent OoM
                    throw new RuntimeException("Invalid string allocation length " + ptxSize + ", expected <1 MB");
                }
            } catch (InteropException e) {
                throw new RuntimeException(e);
            }
        }
        try (UnsafeHelper.StringObject buffer = UnsafeHelper.createStringObject(ptxSize)) {
            try {
                TruffleObject callable = getSymbol(NVRTCFunction.NVRTC_GETPTX);
                Object result = ForeignAccess.sendExecute(executeNode, callable, program.getValue(), buffer.getAddress());
                checkNVRTCReturnCode(result, NVRTCFunction.NVRTC_GETPTX.symbolName);
                return buffer.getZeroTerminatedString();
            } catch (InteropException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public String nvrtcGetLoweredName(NVRTCProgram program, String kernelName) {
        try (UnsafeHelper.PointerObject cString = new UnsafeHelper.PointerObject()) {
            try {
                TruffleObject callable = getSymbol(NVRTCFunction.NVRTC_GETLOWEREDNAME);
                Object result = ForeignAccess.sendExecute(executeNode, callable, program.getValue(),
                                kernelName, cString.getAddress());
                checkNVRTCReturnCode(result, NVRTCFunction.NVRTC_GETLOWEREDNAME.symbolName);
                return UnsafeHelper.StringObject.getUncheckedZeroTerminatedString(cString.getValueOfPointer());
            } catch (InteropException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public String nvrtcGetErrorString(int errorCode) {
        NVRTCFunction function = NVRTCFunction.NVRTC_GETERRORSTRING;
        try {
            TruffleObject callable = getSymbol(function);
            TruffleObject result = (TruffleObject) ForeignAccess.sendExecute(executeNode, callable, errorCode);
            return ForeignAccess.sendUnbox(unboxNode, result).toString();
        } catch (InteropException e) {
            throw new RuntimeException(e);
        }
    }

    public TruffleObject getSymbol(NVRTCFunction function) throws InteropException {
        return runtime.getSymbol(CUDARuntime.NVRTC_LIBRARY_NAME, function.symbolName, function.signature);
    }

    private void checkNVRTCReturnCode(Object result, String functionName) {
        if (!(result instanceof Integer)) {
            throw new RuntimeException(
                            "expected return code as Integer object in " + functionName + ", got " +
                                            result.getClass().getName());
        }
        Integer returnCode = (Integer) result;
        if (returnCode != 0) {
            throw new NVRTCException(returnCode, nvrtcGetErrorString(returnCode), functionName);
        }
    }

    private NVRTCResult toNVRTCResult(Object result) {
        if (!(result instanceof Integer)) {
            throw new RuntimeException(
                            "expected return code as Integer object for nvrtcResult, got " +
                                            result.getClass().getName());
        }
        Integer returnCode = (Integer) result;
        for (NVRTCResult r : NVRTCResult.values()) {
            if (r.errorCode == returnCode) {
                return r;
            }
        }
        return NVRTCResult.NVRTC_UNKNOWN_CODE;
    }

    private class NVRTCProgram implements AutoCloseable {
        UnsafeHelper.PointerObject nvrtcProgram = UnsafeHelper.createPointerObject();
        private boolean initialized = false;

        public long getAddress() {
            return nvrtcProgram.getAddress();
        }

        public long getValue() {
            return nvrtcProgram.getValueOfPointer();
        }

        public void setInitialized() {
            initialized = true;
        }

        @Override
        public void close() {
            if (!initialized) {
                nvrtcProgram.close();
                return;
            }
            NVRTCFunction function = NVRTCFunction.NVRTC_DESTROYPROGRAM;
            try {
                TruffleObject callable = getSymbol(function);
                Object result = ForeignAccess.sendExecute(executeNode, callable,
                                nvrtcProgram.getAddress());
                checkNVRTCReturnCode(result, function.symbolName);
            } catch (InteropException e) {
                throw new RuntimeException(e);
            } finally {
                nvrtcProgram.close();
            }
        }
    }

    public enum NVRTCFunction {
        NVRTC_GETERRORSTRING("nvrtcGetErrorString", "(sint32): string"),
        NVRTC_CREATEPROGRAM("nvrtcCreateProgram", "(pointer, string, string, sint32, pointer, pointer): sint32"),
        NVRTC_DESTROYPROGRAM("nvrtcDestroyProgram", "(pointer): sint32"),
        NVRTC_ADDNAMEDEXPRESSION("nvrtcAddNameExpression", "(pointer, string): sint32"),
        NVRTC_COMPILEPROGRAM("nvrtcCompileProgram", "(pointer, sint32, pointer): sint32"),
        NVRTC_GETPROGRAMLOGSIZE("nvrtcGetProgramLogSize", "(pointer, pointer): sint32"),
        NVRTC_GETPROGRAMLOG("nvrtcGetProgramLog", "(pointer, pointer): sint32"),
        NVRTC_GETLOWEREDNAME("nvrtcGetLoweredName", "(pointer, string, pointer): sint32"),
        NVRTC_GETPTXSIZE("nvrtcGetPTXSize", "(pointer, pointer): sint32"),
        NVRTC_GETPTX("nvrtcGetPTX", "(pointer, pointer): sint32");

        final String symbolName;
        final String signature;

        NVRTCFunction(String symbolName, String signature) {
            this.symbolName = symbolName;
            this.signature = signature;
        }
    }

    /** Error codes from nvrtc.h. */
    public enum NVRTCResult {
        NVRTC_SUCCESS(0),
        NVRTC_ERROR_OUT_OF_MEMORY(1),
        NVRTC_ERROR_PROGRAM_CREATION_FAILURE(2),
        NVRTC_ERROR_INVALID_INPUT(3),
        NVRTC_ERROR_INVALID_PROGRAM(4),
        NVRTC_ERROR_INVALID_OPTION(5),
        NVRTC_ERROR_COMPILATION(6),
        NVRTC_ERROR_BUILTIN_OPTERATION_FAILTURE(7),
        NVRTC_ERROR_NO_NAME_EXPRESSIONS_AFTER_COMPILATION(8),
        NVRTC_ERROR_NO_LOWERED_NAMES_BEFORE_COMPILATION(9),
        NVRTC_ERROR_NAME_EXPRESSION_NOT_VALID(10),
        NVRTC_ERROR_INTERNAL_ERROR(11),
        NVRTC_UNKNOWN_CODE(9999);

        private final int errorCode;

        NVRTCResult(int errorCode) {
            this.errorCode = errorCode;
        }

        public int getErrorCode() {
            return errorCode;
        }

        @Override
        public String toString() {
            return name() + "(" + errorCode + ")";
        }
    }
}

class PTXKernel {
    private final String ptxSource;
    private final String kernelName;
    private final String loweredKernelName;

    PTXKernel(String ptxSource, String kernelName, String loweredKernelName) {
        this.ptxSource = ptxSource;
        this.kernelName = kernelName;
        this.loweredKernelName = loweredKernelName;
    }

    public String getPtxSource() {
        return ptxSource;
    }

    public String getKernelName() {
        return kernelName;
    }

    public String getLoweredKernelName() {
        return loweredKernelName;
    }

    @Override
    public String toString() {
        return "PTXKernel(" + kernelName + "\n" + ptxSource + "\n)";
    }
}