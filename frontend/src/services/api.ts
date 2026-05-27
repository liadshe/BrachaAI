// API Service for communicating with backend
const API_BASE_URL = (import.meta.env.VITE_API_URL as string) || 'http://localhost:3000/api';

interface CallData {
  contactName: string;
  date: string;
  transcript: string;
}

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: string;
}

class ApiService {
  private baseUrl: string;

  constructor(baseUrl: string = API_BASE_URL) {
    this.baseUrl = baseUrl;
  }

  // Calls
  async getCalls(): Promise<ApiResponse<any>> {
    return this.get('/calls');
  }

  async getCall(id: string): Promise<ApiResponse<any>> {
    return this.get(`/calls/${id}`);
  }

  async createCall(data: CallData): Promise<ApiResponse<any>> {
    return this.post('/calls', data);
  }

  // Tasks
  async getTasks(): Promise<ApiResponse<any>> {
    return this.get('/tasks');
  }

  async createTask(data: any): Promise<ApiResponse<any>> {
    return this.post('/tasks', data);
  }

  async updateTask(id: string, data: any): Promise<ApiResponse<any>> {
    return this.put(`/tasks/${id}`, data);
  }

  async deleteTask(id: string): Promise<ApiResponse<any>> {
    return this.delete(`/tasks/${id}`);
  }

  // Contacts
  async getContacts(): Promise<ApiResponse<any>> {
    return this.get('/contacts');
  }

  async createContact(data: any): Promise<ApiResponse<any>> {
    return this.post('/contacts', data);
  }

  async updateContact(id: string, data: any): Promise<ApiResponse<any>> {
    return this.put(`/contacts/${id}`, data);
  }

  // Users
  async login(email: string, password: string): Promise<ApiResponse<any>> {
    return this.post('/users/login', { email, password });
  }

  async signup(data: any): Promise<ApiResponse<any>> {
    return this.post('/users/signup', data);
  }

  async getProfile(): Promise<ApiResponse<any>> {
    return this.get('/users/profile');
  }

  // Generic HTTP methods
  private async get<T>(endpoint: string): Promise<ApiResponse<T>> {
    try {
      const response = await fetch(`${this.baseUrl}${endpoint}`, {
        method: 'GET',
        headers: this.getHeaders(),
      });
      return this.handleResponse(response);
    } catch (error) {
      return this.handleError(error);
    }
  }

  private async post<T>(endpoint: string, data: any): Promise<ApiResponse<T>> {
    try {
      const response = await fetch(`${this.baseUrl}${endpoint}`, {
        method: 'POST',
        headers: this.getHeaders(),
        body: JSON.stringify(data),
      });
      return this.handleResponse(response);
    } catch (error) {
      return this.handleError(error);
    }
  }

  private async put<T>(endpoint: string, data: any): Promise<ApiResponse<T>> {
    try {
      const response = await fetch(`${this.baseUrl}${endpoint}`, {
        method: 'PUT',
        headers: this.getHeaders(),
        body: JSON.stringify(data),
      });
      return this.handleResponse(response);
    } catch (error) {
      return this.handleError(error);
    }
  }

  private async delete<T>(endpoint: string): Promise<ApiResponse<T>> {
    try {
      const response = await fetch(`${this.baseUrl}${endpoint}`, {
        method: 'DELETE',
        headers: this.getHeaders(),
      });
      return this.handleResponse(response);
    } catch (error) {
      return this.handleError(error);
    }
  }

  private getHeaders(): Record<string, string> {
    const token = localStorage.getItem('authToken');
    return {
      'Content-Type': 'application/json',
      ...(token && { Authorization: `Bearer ${token}` }),
    };
  }

  private async handleResponse<T>(response: Response): Promise<ApiResponse<T>> {
    const data = await response.json();
    if (response.ok) {
      return { success: true, data };
    }
    return { success: false, error: data.message || 'An error occurred' };
  }

  private handleError(error: any): ApiResponse<any> {
    return {
      success: false,
      error: error instanceof Error ? error.message : 'An error occurred',
    };
  }
}

export default new ApiService();
