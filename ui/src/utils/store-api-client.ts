import axios from "axios";
import type { AxiosError } from "axios";
import qs from "qs";
import {Toast} from "@halo-dev/components";

const storeApiClient = axios.create({
  baseURL: 'https://www.yunext.cn',
  paramsSerializer: (params) => {
    return qs.stringify(params, { arrayFormat: "repeat" });
  },
});
storeApiClient.interceptors.response.use(
  (response) => {
    return response;
  },
  async (error: AxiosError<ProblemDetail>) => {
    if (/Network Error/.test(error.message)) {
      Toast.error("网络错误，请检查网络连接");
      return Promise.reject(error);
    }

    const errorResponse = error.response;

    if (!errorResponse) {
      Toast.error("网络错误，请检查网络连接");
      return Promise.reject(error);
    }
    
    const { title, detail } = errorResponse.data;
    
    if (title || detail) {
      Toast.error(detail || title);
      return Promise.reject(error);
    }

    Toast.error("未知错误");

    return Promise.reject(error);
  }
);

export interface ProblemDetail {
  detail: string;
  instance: string;
  status: number;
  title: string;
  type?: string;
}

export default storeApiClient;
