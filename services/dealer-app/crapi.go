/*
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// The slices of crAPI's API responses that DealerConnect reads, and helpers to
// fetch them with a user's access token.

package main

import "context"

// Vehicle mirrors an entry from GET /identity/api/v2/vehicle/vehicles.
type Vehicle struct {
	VIN    string `json:"vin"`
	Year   int    `json:"year"`
	Status string `json:"status"`
	Model  struct {
		Model        string `json:"model"`
		FuelType     string `json:"fuel_type"`
		VehicleImg   string `json:"vehicle_img"`
		VehicleOwner struct {
			Name string `json:"name"`
		} `json:"vehiclecompany"`
	} `json:"model"`
	Location struct {
		Latitude  string `json:"latitude"`
		Longitude string `json:"longitude"`
	} `json:"vehicleLocation"`
}

// Product mirrors an entry from GET /workshop/api/shop/products.
type Product struct {
	ID       int    `json:"id"`
	Name     string `json:"name"`
	Price    string `json:"price"`
	ImageURL string `json:"image_url"`
}

type productsResponse struct {
	Products []Product `json:"products"`
	Credit   float64   `json:"credit"`
}

// UserInfo mirrors GET /identity/api/oauth/userinfo.
type UserInfo struct {
	Sub    string `json:"sub"`
	Email  string `json:"email"`
	Name   string `json:"name"`
	Number string `json:"number"`
	Role   string `json:"role"`
}

func (c *OAuthClient) UserInfo(ctx context.Context, token string) (*UserInfo, error) {
	var info UserInfo
	if err := c.Get(ctx, token, "/identity/api/oauth/userinfo", &info); err != nil {
		return nil, err
	}
	return &info, nil
}

func (c *OAuthClient) Vehicles(ctx context.Context, token string) ([]Vehicle, error) {
	var vehicles []Vehicle
	if err := c.Get(ctx, token, "/identity/api/v2/vehicle/vehicles", &vehicles); err != nil {
		return nil, err
	}
	return vehicles, nil
}

func (c *OAuthClient) Shop(ctx context.Context, token string) ([]Product, float64, error) {
	var resp productsResponse
	if err := c.Get(ctx, token, "/workshop/api/shop/products", &resp); err != nil {
		return nil, 0, err
	}
	return resp.Products, resp.Credit, nil
}

// PlaceOrder buys a product through crAPI's shop (needs the write:orders scope).
func (c *OAuthClient) PlaceOrder(ctx context.Context, token string, productID, quantity int) error {
	body := map[string]int{"product_id": productID, "quantity": quantity}
	return c.PostJSON(ctx, token, "/workshop/api/shop/orders", body, nil)
}
